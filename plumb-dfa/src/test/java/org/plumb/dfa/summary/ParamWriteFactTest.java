/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.dfa.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The summary's write-back facts: the taint a function writes INTO a formal parameter.
 *
 * <p><b>Why this is a dimension of its own.</b> A by-reference argument (C#'s {@code out} / {@code
 * ref}) is written by the callee and read by the caller, so the value that crosses the call
 * boundary travels through the FORMAL rather than through a result. {@code paramOps} says what the
 * function does with a parameter's value and {@code returnOps} says what comes back through a
 * result; neither of them can say "this function LEAVES this taint in this parameter". Without that
 * fact, a caller that passed an {@code out} variable had no way to learn that the callee wrote
 * tainted data into it.
 *
 * <p><b>Why the cause matters.</b> "This function writes tainted data into its first parameter" is
 * not a statement about the function alone -- it is a statement about the function GIVEN which of
 * its parameters were tainted. A helper that copies its first argument into an {@code out} argument
 * is harmless when the caller passes a clean value and a finding when the caller passes a tainted
 * one. So the summary records WHICH formal the written taint flows through, and the projection
 * narrows the fact to the call sites that feed that formal with taint.
 *
 * <p>A write whose taint came from NO formal -- the callee read a source of its own -- has no
 * cause, and it fires at every call site: there is nothing to narrow it by.
 *
 * <p><b>Why the copy points are the interesting part.</b> The engine stores one summary under
 * SEVERAL keys, and every copy has to carry the same facts. A copy that lost the write-back keeps
 * the taint at every call site -- a false positive rather than a missed one -- and a copy that lost
 * the cause keeps it at every call site too.
 */
@DisplayName("Summary: the taint a function writes into a formal parameter")
class ParamWriteFactTest {

  /** The write-back fact of a function whose source is its own. */
  private static final Operation.TaintPropagate WRITE =
      new Operation.TaintPropagate("out:value in P#read(out string)", 1.0);

  @Test
  @DisplayName("a write into a formal is a fact, so the summary is not trivial")
  void aWriteIntoAFormalIsAFact() {
    FunctionSummary s =
        new FunctionSummary.Builder("P#read(out string)").recordParamWriteOp(0, WRITE).build();

    assertFalse(
        s.isTrivial(),
        "a summary whose only fact is \"this writes tainted data into a formal\" carries"
            + " something");
    assertEquals(1, s.paramWriteOpsAt(0).size());
    assertTrue(s.paramWriteCausesAt(0).isEmpty(), "the write came from no formal");
    assertTrue(s.paramWriteOpsAt(1).isEmpty(), "no other formal was written");
  }

  @Test
  @DisplayName("a write whose taint came from no formal fires at every call site")
  void aWriteWithNoCauseFiresAtEveryCallSite() {
    FunctionSummary s =
        new FunctionSummary.Builder("P#read(out string)").recordParamWriteOp(0, WRITE).build();

    FunctionSummary projected = s.projectThrough(CallSiteBinding.NONE);

    assertEquals(
        1,
        projected.paramWriteOpsAt(0).size(),
        "there is no formal to narrow this write by, so it has to survive every call site");
  }

  @Test
  @DisplayName(
      "a write whose taint flowed through a formal fires only where that formal is tainted")
  void aWriteWhoseCauseIsNotTaintedHereIsDropped() {
    FunctionSummary s =
        new FunctionSummary.Builder("P#copy(string,out string)")
            .recordParamWriteCause(1, 0)
            .build();

    FunctionSummary projected = s.projectThrough(CallSiteBinding.NONE);

    assertTrue(
        projected.paramWriteCausesAt(1).isEmpty(),
        "nothing is tainted at this call site, so the write-back cannot be live here");
  }

  @Test
  @DisplayName("a write whose taint flowed through a formal survives where that formal is tainted")
  void aWriteWhoseCauseIsTaintedHereSurvives() {
    FunctionSummary s =
        new FunctionSummary.Builder("P#copy(string,out string)")
            .recordParamWriteCause(1, 0)
            .build();

    FunctionSummary projected = s.projectThrough(CallSiteBinding.of(Map.of(0, 0)));

    assertEquals(Set.of(0), projected.paramWriteCausesAt(1));
  }

  @Test
  @DisplayName("every copy of the summary carries the write-back")
  void everyCopyCarriesTheWriteBack() {
    FunctionSummary s =
        new FunctionSummary.Builder("P#copy(string,out string)")
            .recordParamWriteOp(1, WRITE)
            .recordParamWriteCause(1, 0)
            .build();

    assertEquals(1, s.aliasedAs("P#copy(string,out string)").paramWriteOpsAt(1).size());
    assertEquals(Set.of(0), s.aliasedAs("P#copy(string,out string)").paramWriteCausesAt(1));
    assertEquals(1, s.project(op -> true).paramWriteOpsAt(1).size());
    assertEquals(Set.of(0), s.project(op -> true).paramWriteCausesAt(1));
  }

  @Test
  @DisplayName("a merge of two summaries carries both sides' writes")
  void aMergeCarriesBothSidesWrites() {
    FunctionSummary one =
        new FunctionSummary.Builder("P#copy(string,out string)")
            .recordParamWriteOp(1, WRITE)
            .recordParamWriteCause(1, 0)
            .build();
    FunctionSummary other =
        new FunctionSummary.Builder("P#copy(string,out string)")
            .recordParamWriteOp(
                0, new Operation.TaintPropagate("out:input in P#copy(string,out string)", 1.0))
            .build();

    FunctionSummary merged = one.merge(other);

    assertEquals(1, merged.paramWriteOpsAt(1).size());
    assertEquals(Set.of(0), merged.paramWriteCausesAt(1));
    assertEquals(1, merged.paramWriteOpsAt(0).size());
  }

  @Test
  @DisplayName("a merge with an empty summary keeps the write-back")
  void aMergeWithAnEmptySummaryKeepsTheWriteBack() {
    FunctionSummary s =
        new FunctionSummary.Builder("P#copy(string,out string)")
            .recordParamWriteOp(1, WRITE)
            .recordParamWriteCause(1, 0)
            .build();

    FunctionSummary merged = s.merge(FunctionSummary.empty());

    assertEquals(1, merged.paramWriteOpsAt(1).size());
    assertEquals(Set.of(0), merged.paramWriteCausesAt(1));
  }
}

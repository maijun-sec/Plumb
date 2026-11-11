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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FunctionSummary}.
 *
 * <p>Covers the contract:
 *
 * <ol>
 *   <li>Empty / trivial summary is a single shared instance.
 *   <li>Builder accumulates param + return operations and callee signatures.
 *   <li>{@link FunctionSummary#merge(FunctionSummary)} unions both sides; the unmodelled flag
 *       sticks if either side has it.
 *   <li>{@link FunctionSummary#project(java.util.function.Predicate)} filters out non-matching
 *       operations while preserving the signature.
 *   <li>Validation: a {@link Operation.Sink} with a negative arg index or a {@link
 *       Operation.TaintPropagate} with out-of-range confidence is rejected at construction time,
 *       not at use time.
 * </ol>
 */
class FunctionSummaryTest {

  @Test
  void emptyIsSingletonAndTrivial() {
    FunctionSummary e1 = FunctionSummary.empty();
    FunctionSummary e2 = FunctionSummary.empty();
    // Same reference (cheap) so consumers can
    // safely use == as well as equals().
    assertSame(e1, e2);
    assertTrue(e1.isTrivial());
    assertFalse(e1.hasUnmodelledBehavior());
  }

  @Test
  void builderRecordsParamAndReturnOps() {
    FunctionSummary s =
        new FunctionSummary.Builder("M#m(int,String)")
            .recordParamOp(0, new Operation.Sink("sql.sinks", 0))
            .recordParamOp(1, new Operation.TaintPropagate("bu-0001-req", 1.0))
            .recordReturnOp(new Operation.PassThrough())
            .recordCallee("Helper#helper()")
            .build();

    assertEquals("M#m(int,String)", s.signature());
    assertFalse(s.isTrivial());
    assertEquals(2, s.paramOpsAt(0).size() + s.paramOpsAt(1).size());
    assertTrue(s.paramOpsAt(0).contains(new Operation.Sink("sql.sinks", 0)));
    assertTrue(s.paramOpsAt(1).contains(new Operation.TaintPropagate("bu-0001-req", 1.0)));
    assertEquals(1, s.returnOps().size());
    assertTrue(s.returnOps().contains(Operation.PassThrough.INSTANCE));
    assertTrue(s.calleesReached().contains("Helper#helper()"));
  }

  @Test
  void unmodelledBehaviorFlagSticks() {
    FunctionSummary s =
        new FunctionSummary.Builder("M#m()")
            .recordParamOp(0, new Operation.HasUnmodelledBehavior("native"))
            .build();
    assertTrue(s.hasUnmodelledBehavior());
  }

  @Test
  void mergeUnionsBothSides() {
    FunctionSummary a =
        new FunctionSummary.Builder("M#m()")
            .recordParamOp(0, new Operation.Sink("sql.sinks", 0))
            .build();
    FunctionSummary b =
        new FunctionSummary.Builder("M#m()")
            .recordParamOp(1, new Operation.Sink("command.sinks", 0))
            .recordCallee("H#h()")
            .build();

    FunctionSummary merged = a.merge(b);
    // Both operations must be present.
    assertTrue(merged.paramOpsAt(0).contains(new Operation.Sink("sql.sinks", 0)));
    assertTrue(merged.paramOpsAt(1).contains(new Operation.Sink("command.sinks", 0)));
    assertTrue(merged.calleesReached().contains("H#h()"));
  }

  @Test
  void mergeUnionsUnmodelledFlag() {
    FunctionSummary unmodelled = new FunctionSummary.Builder("M#m()").markUnmodelled().build();
    FunctionSummary normal =
        new FunctionSummary.Builder("M#m()")
            .recordParamOp(0, new Operation.Sink("sql.sinks", 0))
            .build();

    assertTrue(unmodelled.merge(normal).hasUnmodelledBehavior());
    assertTrue(normal.merge(unmodelled).hasUnmodelledBehavior());
  }

  @Test
  void mergeWithEmptyReturnsOther() {
    FunctionSummary real =
        new FunctionSummary.Builder("M#m()")
            .recordParamOp(0, new Operation.Sink("sql.sinks", 0))
            .build();
    assertEquals(real, real.merge(FunctionSummary.empty()));
    assertEquals(real, FunctionSummary.empty().merge(real));
  }

  @Test
  void projectFiltersOperations() {
    FunctionSummary s =
        new FunctionSummary.Builder("M#m()")
            .recordParamOp(0, new Operation.Sink("sql.sinks", 0))
            .recordParamOp(0, new Operation.Sink("command.sinks", 0))
            .recordParamOp(1, new Operation.Sink("sql.sinks", 1))
            .recordReturnOp(new Operation.Sink("sql.sinks", 0))
            .build();

    // SQL-only consumer projection.
    FunctionSummary sqlOnly =
        s.project(op -> op instanceof Operation.Sink sk && "sql.sinks".equals(sk.familyKey()));
    assertTrue(sqlOnly.paramOpsAt(0).contains(new Operation.Sink("sql.sinks", 0)));
    assertFalse(
        sqlOnly.paramOpsAt(0).stream()
            .anyMatch(
                op -> op instanceof Operation.Sink sk && "command.sinks".equals(sk.familyKey())));
    assertTrue(sqlOnly.paramOpsAt(1).contains(new Operation.Sink("sql.sinks", 1)));
    assertTrue(sqlOnly.returnOps().contains(new Operation.Sink("sql.sinks", 0)));
  }

  @Test
  void sinkRejectsNegativeArgIndex() {
    assertThrows(IllegalArgumentException.class, () -> new Operation.Sink("sql.sinks", -1));
  }

  @Test
  void taintPropagateRejectsOutOfRangeConfidence() {
    assertThrows(
        IllegalArgumentException.class, () -> new Operation.TaintPropagate("bu-0001", -0.1));
    assertThrows(
        IllegalArgumentException.class, () -> new Operation.TaintPropagate("bu-0001", 1.1));
  }

  @Test
  void sinkAndTaintAcceptValidInputs() {
    // Happy path; not redundant with the
    // rejection tests above because constructors
    // do work as well as validation.
    assertNotNull(new Operation.Sink("sql.sinks", 0));
    assertNotNull(new Operation.TaintPropagate("bu-0001", 0.5));
  }

  @Test
  void aCopyUnderAnotherSignatureKeepsTheReturnTaintCauses() {
    // The engine stores one summary under SEVERAL keys -- the function's own signature, the key
    // that names the declaring class, the canonical key -- and every one of them has to carry the
    // same facts.
    //
    // The field that matters is the return taint's CAUSES: it says which formal parameter the
    // return taint flows through, and the context-sensitive projection uses it to decide whether
    // the return taint is reachable at a call site. A copy without it keeps the return taint at
    // EVERY call site, including the ones that pass nothing tainted.
    FunctionSummary summary =
        new FunctionSummary.Builder("echo(String):String")
            .recordParamOp(0, new Operation.TaintPropagate("param:v in echo", 1.0))
            .recordReturnOp(new Operation.TaintPropagate("return:v in echo", 1.0))
            .recordReturnTaintCause(0)
            .build();

    FunctionSummary alias = summary.aliasedAs("Helper :: echo/1");

    assertEquals("Helper :: echo/1", alias.signature());
    assertTrue(alias.hasBody(), "the alias key exists to say the function HAS a body");
    assertEquals(
        java.util.Set.of(0),
        alias.returnTaintCauses(),
        "the copy has to keep the return taint's causes, or the projection cannot narrow it");
    assertTrue(alias.returnOps().contains(new Operation.TaintPropagate("return:v in echo", 1.0)));
    assertTrue(
        alias.projectThrough(CallSiteBinding.NONE).returnOps().isEmpty(),
        "a call site that taints nothing must be able to rule the return taint out");
    assertFalse(
        alias.projectThrough(CallSiteBinding.of(java.util.Map.of(0, 0))).returnOps().isEmpty(),
        "a call site that taints the parameter must keep the return taint");
  }
}

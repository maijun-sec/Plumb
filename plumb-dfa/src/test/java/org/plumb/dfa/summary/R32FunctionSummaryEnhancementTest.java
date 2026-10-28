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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code stringOps} and {@code fieldOps} enhancement to {@link FunctionSummary}.
 *
 * <p>These tests cover:
 *
 * <ol>
 *   <li>The new {@link StringOp} and {@link FieldOp} record types: validation, known-passthrough
 *       membership, equality.
 *   <li>The {@link FunctionSummary.Builder} methods: {@code recordStringOp} and {@code
 *       recordFieldOp} (null-safety, accumulation, ordering).
 *   <li>The {@link FunctionSummary} accessors: {@code stringOps()} and {@code fieldOps()} return
 *       unmodifiable views in observed order.
 *   <li>{@code isTrivial()} recognises stringOps / fieldOps as non-trivial.
 *   <li>{@code merge()} unions both lists in order, preserving the receiver-first ordering
 *       convention.
 *   <li>{@code project()} preserves both lists (they're not Operation subtypes, so the
 *       Operation-based filter doesn't apply).
 *   <li>The empty summary has empty lists (not null) and is trivial.
 * </ol>
 */
class R32FunctionSummaryEnhancementTest {

  // ---------------- StringOp ----------------

  @Test
  void stringOpRejectsNullMethodName() {
    assertThrows(NullPointerException.class, () -> new StringOp(null));
  }

  @Test
  void stringOpRejectsEmptyMethodName() {
    assertThrows(IllegalArgumentException.class, () -> new StringOp(""));
  }

  @Test
  void stringOpEqualityAndHashCode() {
    // Records auto-generate equals / hashCode;
    // we just confirm the contract.
    StringOp a = new StringOp("toLowerCase");
    StringOp b = new StringOp("toLowerCase");
    StringOp c = new StringOp("trim");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertFalse(a.equals(c));
  }

  @Test
  void stringOpIsKnownPassthrough() {
    assertTrue(new StringOp("toLowerCase").isKnownPassthrough());
    assertTrue(new StringOp("toUpperCase").isKnownPassthrough());
    assertTrue(new StringOp("trim").isKnownPassthrough());
    assertTrue(new StringOp("substring").isKnownPassthrough());
    assertTrue(new StringOp("concat").isKnownPassthrough());
    assertTrue(new StringOp("valueOf").isKnownPassthrough());
    assertTrue(new StringOp("split").isKnownPassthrough());
    // A method NOT in the known list still constructs
    // successfully; isKnownPassthrough returns false.
    // The two-pass analyzer can still walk the
    // list and treat unknown methods conservatively.
    assertFalse(new StringOp("someArbitraryMethod").isKnownPassthrough());
  }

  @Test
  void stringOpKnownPassthroughsSetIncludesExpectedEntries() {
    // Smoke test: the set is non-empty and contains
    // the documented passthroughs.
    java.util.Set<String> passthroughs = StringOp.knownPassthroughs();
    assertNotNull(passthroughs);
    assertFalse(passthroughs.isEmpty());
    assertTrue(passthroughs.contains("toLowerCase"));
    assertTrue(passthroughs.contains("toUpperCase"));
    assertTrue(passthroughs.contains("trim"));
    assertTrue(passthroughs.contains("substring"));
  }

  // ---------------- FieldOp ----------------

  @Test
  void fieldOpRejectsNullFieldName() {
    assertThrows(NullPointerException.class, () -> new FieldOp(null, 0));
  }

  @Test
  void fieldOpRejectsEmptyFieldName() {
    assertThrows(IllegalArgumentException.class, () -> new FieldOp("", 0));
  }

  @Test
  void fieldOpRejectsNegativeSourceParamIndex() {
    // -1 is the documented NON_PARAM_SOURCE sentinel;
    // anything below -1 is invalid.
    assertThrows(IllegalArgumentException.class, () -> new FieldOp("data", -2));
    assertThrows(IllegalArgumentException.class, () -> new FieldOp("data", -100));
  }

  @Test
  void fieldOpAcceptsNegativeOneAsNonParamSource() {
    // -1 is the documented "non-param source" sentinel.
    FieldOp op = new FieldOp("data", FieldOp.NON_PARAM_SOURCE);
    assertEquals(FieldOp.NON_PARAM_SOURCE, op.sourceParamIndex());
    assertFalse(op.hasParamSource());
  }

  @Test
  void fieldOpHasParamSource() {
    FieldOp fromParam = FieldOp.fromParam("data", 0);
    assertTrue(fromParam.hasParamSource());
    assertEquals(0, fromParam.sourceParamIndex());
    assertEquals("data", fromParam.fieldName());
  }

  @Test
  void fieldOpFromParamRejectsNegativeParamIndex() {
    assertThrows(IllegalArgumentException.class, () -> FieldOp.fromParam("data", -1));
  }

  @Test
  void fieldOpFromLocalSource() {
    FieldOp op = FieldOp.fromLocalSource("data");
    assertFalse(op.hasParamSource());
    assertEquals("data", op.fieldName());
    assertEquals(FieldOp.NON_PARAM_SOURCE, op.sourceParamIndex());
  }

  @Test
  void fieldOpEqualityAndHashCode() {
    FieldOp a = FieldOp.fromParam("data", 0);
    FieldOp b = FieldOp.fromParam("data", 0);
    FieldOp c = FieldOp.fromParam("data", 1);
    FieldOp d = FieldOp.fromLocalSource("data");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertFalse(a.equals(c));
    assertFalse(a.equals(d));
  }

  // ---------------- Builder: StringOp ----------------

  @Test
  void builderRecordsStringOpInOrder() {
    FunctionSummary s =
        new FunctionSummary.Builder("M#m(String)")
            .recordStringOp(new StringOp("toLowerCase"))
            .recordStringOp(new StringOp("trim"))
            .build();
    assertEquals(2, s.stringOps().size());
    assertEquals("toLowerCase", s.stringOps().get(0).methodName());
    assertEquals("trim", s.stringOps().get(1).methodName());
  }

  @Test
  void builderIgnoresNullStringOp() {
    FunctionSummary s =
        new FunctionSummary.Builder("M#m()")
            .recordStringOp(null)
            .recordStringOp(new StringOp("trim"))
            .build();
    assertEquals(1, s.stringOps().size());
    assertEquals("trim", s.stringOps().get(0).methodName());
  }

  // ---------------- Builder: FieldOp ----------------

  @Test
  void builderRecordsFieldOpInOrder() {
    FunctionSummary s =
        new FunctionSummary.Builder("M#m(String)")
            .recordFieldOp(FieldOp.fromParam("data", 0))
            .recordFieldOp(FieldOp.fromLocalSource("count"))
            .build();
    assertEquals(2, s.fieldOps().size());
    assertEquals("data", s.fieldOps().get(0).fieldName());
    assertEquals(0, s.fieldOps().get(0).sourceParamIndex());
    assertEquals("count", s.fieldOps().get(1).fieldName());
    assertEquals(FieldOp.NON_PARAM_SOURCE, s.fieldOps().get(1).sourceParamIndex());
  }

  @Test
  void builderIgnoresNullFieldOp() {
    FunctionSummary s =
        new FunctionSummary.Builder("M#m()")
            .recordFieldOp(null)
            .recordFieldOp(FieldOp.fromParam("data", 0))
            .build();
    assertEquals(1, s.fieldOps().size());
  }

  @Test
  void builderAllowsDuplicateStringOps() {
    // The two-pass analyzer
    // may need to distinguish
    // `arg.toLowerCase().trim()` from
    // `arg.trim().toLowerCase()`, so duplicates
    // are NOT deduplicated.
    FunctionSummary s =
        new FunctionSummary.Builder("M#m()")
            .recordStringOp(new StringOp("trim"))
            .recordStringOp(new StringOp("trim"))
            .build();
    assertEquals(2, s.stringOps().size());
  }

  @Test
  void builderAllowsDuplicateFieldOps() {
    // Multiple writes to the same
    // field may have different sources; the
    // tracker needs the full list.
    FunctionSummary s =
        new FunctionSummary.Builder("M#m()")
            .recordFieldOp(FieldOp.fromParam("data", 0))
            .recordFieldOp(FieldOp.fromParam("data", 1))
            .build();
    assertEquals(2, s.fieldOps().size());
    assertEquals(0, s.fieldOps().get(0).sourceParamIndex());
    assertEquals(1, s.fieldOps().get(1).sourceParamIndex());
  }

  // ---------------- Accessors ----------------

  @Test
  void stringOpsReturnsUnmodifiableList() {
    FunctionSummary s =
        new FunctionSummary.Builder("M#m()").recordStringOp(new StringOp("toLowerCase")).build();
    java.util.List<StringOp> ops = s.stringOps();
    // Unmodifiable: any mutation throws.
    assertThrows(UnsupportedOperationException.class, () -> ops.add(new StringOp("trim")));
  }

  @Test
  void fieldOpsReturnsUnmodifiableList() {
    FunctionSummary s =
        new FunctionSummary.Builder("M#m()").recordFieldOp(FieldOp.fromParam("data", 0)).build();
    java.util.List<FieldOp> ops = s.fieldOps();
    assertThrows(UnsupportedOperationException.class, () -> ops.add(FieldOp.fromParam("other", 0)));
  }

  // ---------------- isTrivial ----------------

  @Test
  void isTrivialTrueForEmptySummary() {
    assertTrue(FunctionSummary.empty().isTrivial());
  }

  @Test
  void isTrivialFalseWhenStringOpPresent() {
    FunctionSummary s =
        new FunctionSummary.Builder("M#m()").recordStringOp(new StringOp("toLowerCase")).build();
    assertFalse(s.isTrivial());
  }

  @Test
  void isTrivialFalseWhenFieldOpPresent() {
    FunctionSummary s =
        new FunctionSummary.Builder("M#m()").recordFieldOp(FieldOp.fromParam("data", 0)).build();
    assertFalse(s.isTrivial());
  }

  // ---------------- merge ----------------

  @Test
  void mergeUnionsStringOpsInOrder() {
    FunctionSummary a =
        new FunctionSummary.Builder("M#m()")
            .recordStringOp(new StringOp("toLowerCase"))
            .recordStringOp(new StringOp("trim"))
            .build();
    FunctionSummary b =
        new FunctionSummary.Builder("M#m()").recordStringOp(new StringOp("substring")).build();
    FunctionSummary merged = a.merge(b);
    assertEquals(3, merged.stringOps().size());
    // Receiver-first ordering: a's entries come first.
    assertEquals("toLowerCase", merged.stringOps().get(0).methodName());
    assertEquals("trim", merged.stringOps().get(1).methodName());
    assertEquals("substring", merged.stringOps().get(2).methodName());
  }

  @Test
  void mergeUnionsFieldOpsInOrder() {
    FunctionSummary a =
        new FunctionSummary.Builder("M#m()").recordFieldOp(FieldOp.fromParam("a", 0)).build();
    FunctionSummary b =
        new FunctionSummary.Builder("M#m()")
            .recordFieldOp(FieldOp.fromParam("b", 1))
            .recordFieldOp(FieldOp.fromLocalSource("c"))
            .build();
    FunctionSummary merged = a.merge(b);
    assertEquals(3, merged.fieldOps().size());
    assertEquals("a", merged.fieldOps().get(0).fieldName());
    assertEquals("b", merged.fieldOps().get(1).fieldName());
    assertEquals("c", merged.fieldOps().get(2).fieldName());
  }

  @Test
  void mergeWithTrivialReturnsReceiver() {
    // The merge() short-circuit for trivial
    // summaries must apply to stringOps /
    // fieldOps too: merging a non-trivial
    // summary with an empty one returns the
    // non-trivial one.
    FunctionSummary a =
        new FunctionSummary.Builder("M#m()")
            .recordStringOp(new StringOp("trim"))
            .recordFieldOp(FieldOp.fromParam("data", 0))
            .build();
    FunctionSummary merged = a.merge(FunctionSummary.empty());
    // The reference may be `a` itself (per the
    // merge short-circuit) or a new summary
    // with the same content; we accept both.
    assertEquals(1, merged.stringOps().size());
    assertEquals(1, merged.fieldOps().size());
  }

  // ---------------- project ----------------

  @Test
  void projectPreservesStringOpsAndFieldOps() {
    // StringOp / FieldOp are not Operation
    // subtypes; the Operation-based filter
    // doesn't apply, so project() must
    // preserve them as-is.
    FunctionSummary s =
        new FunctionSummary.Builder("M#m(String)")
            .recordParamOp(0, new Operation.Sink("sql.sinks", 0))
            .recordStringOp(new StringOp("toLowerCase"))
            .recordFieldOp(FieldOp.fromParam("data", 0))
            .build();
    FunctionSummary projected =
        s.project(
            op ->
                op instanceof Operation.Sink
                    && ((Operation.Sink) op).familyKey().equals("sql.sinks"));
    assertEquals(1, projected.stringOps().size());
    assertEquals(1, projected.fieldOps().size());
    assertEquals("toLowerCase", projected.stringOps().get(0).methodName());
    assertEquals("data", projected.fieldOps().get(0).fieldName());
  }

  // ---------------- toString ----------------

  @Test
  void toStringIncludesNewCounts() {
    FunctionSummary s =
        new FunctionSummary.Builder("M#m()")
            .recordStringOp(new StringOp("trim"))
            .recordFieldOp(FieldOp.fromParam("data", 0))
            .build();
    String str = s.toString();
    assertTrue(str.contains("stringOps=1"), "toString should mention stringOps=1: " + str);
    assertTrue(str.contains("fieldOps=1"), "toString should mention fieldOps=1: " + str);
  }

  // ---------------- Empty summary ----------------

  @Test
  void emptySummaryHasEmptyListsNotNull() {
    FunctionSummary e = FunctionSummary.empty();
    assertNotNull(e.stringOps());
    assertNotNull(e.fieldOps());
    assertTrue(e.stringOps().isEmpty());
    assertTrue(e.fieldOps().isEmpty());
  }
}

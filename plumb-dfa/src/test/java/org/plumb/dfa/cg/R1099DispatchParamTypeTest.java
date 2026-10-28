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
package org.plumb.dfa.cg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.TacType;

/**
 * R1099 regression gate: virtual dispatch must respect parameter TYPES, not just name and arity.
 *
 * <p>{@code VtaCallGraphStrategy} indexed candidate overrides by {@code method/arity}. Two
 * overloads — {@code process(int)} and {@code process(String)} — therefore shared one key, so a
 * call site passing an {@code int} could bind to the {@code String} overload. The primitive then
 * appeared to flow into a string sink and produced a provable CWE-89 false positive.
 *
 * <p>These tests pin the assignability rule the dispatch filter relies on. The rule is deliberately
 * asymmetric: it rejects only what is provably impossible (primitive vs. concrete reference, two
 * different primitives) and accepts everything the IR cannot decide, so imprecision widens the call
 * graph rather than silently dropping real edges.
 */
class R1099DispatchParamTypeTest {

  private static TacType t(TacType.Kind k) {
    return switch (k) {
      case STRING -> TacType.STRING;
      case INT -> TacType.INT;
      case LONG -> TacType.LONG;
      case BOOLEAN -> TacType.BOOLEAN;
      case CLASS -> TacType.ofClass("java.lang.Object");
      default -> TacType.ofClass("java.lang.Object");
    };
  }

  @Test
  void intArgumentIsNotAssignableToStringParameter() {
    // This is the exact FP: `numbers.process(7)` binding to `process(String)`.
    assertThat(
            VtaCallGraphStrategy.isAssignableForTest(t(TacType.Kind.INT), t(TacType.Kind.STRING)))
        .as("a primitive int must not be treated as assignable to a String parameter")
        .isFalse();
  }

  @Test
  void samePrimitiveIsAssignable() {
    assertThat(VtaCallGraphStrategy.isAssignableForTest(t(TacType.Kind.INT), t(TacType.Kind.INT)))
        .isTrue();
  }

  @Test
  void differentPrimitivesAreNotAssignable() {
    assertThat(VtaCallGraphStrategy.isAssignableForTest(t(TacType.Kind.INT), t(TacType.Kind.LONG)))
        .as("int does not widen to long implicitly at a call site the engine models")
        .isFalse();
    assertThat(
            VtaCallGraphStrategy.isAssignableForTest(t(TacType.Kind.INT), t(TacType.Kind.BOOLEAN)))
        .isFalse();
  }

  @Test
  void referenceToReferenceIsAlwaysAccepted() {
    // No subtype lattice is available, so every reference pairing stays admissible.
    assertThat(
            VtaCallGraphStrategy.isAssignableForTest(t(TacType.Kind.STRING), t(TacType.Kind.CLASS)))
        .isTrue();
    assertThat(
            VtaCallGraphStrategy.isAssignableForTest(t(TacType.Kind.CLASS), t(TacType.Kind.STRING)))
        .isTrue();
  }

  @Test
  void unknownTypesNeverReject() {
    // An imprecise operand must widen the graph, never narrow it.
    assertThat(VtaCallGraphStrategy.isAssignableForTest(null, t(TacType.Kind.STRING))).isTrue();
    assertThat(VtaCallGraphStrategy.isAssignableForTest(t(TacType.Kind.INT), null)).isTrue();
    assertThat(VtaCallGraphStrategy.isAssignableForTest(null, null)).isTrue();
  }
}

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
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link TaintDomain} sealed interface.
 *
 * <p>Verifies the four domain subtypes have the expected labels, the parser handles all valid
 * string forms (case-insensitive, aliases accepted), the {@link TaintDomain#join} helper produces
 * {@link TaintDomain.MixedTaint} on different-domain joins, and the {@link TaintDomain#flowsInto}
 * relation behaves as a proper "could match" predicate ({@link TaintDomain.MixedTaint} flows
 * anywhere, a specific domain only flows to itself or to {@link TaintDomain.MixedTaint}).
 */
class TaintDomainTest {

  @Test
  void stringTaint_defaultLabel() {
    assertThat(TaintDomain.StringTaint.INSTANCE.label()).isEqualTo("STRING");
  }

  @Test
  void numericTaint_defaultLabel() {
    assertThat(TaintDomain.NumericTaint.INSTANCE.label()).isEqualTo("NUMERIC");
  }

  @Test
  void pointerTaint_defaultLabel() {
    assertThat(TaintDomain.PointerTaint.INSTANCE.label()).isEqualTo("POINTER");
  }

  @Test
  void mixedTaint_defaultLabel() {
    assertThat(TaintDomain.MixedTaint.INSTANCE.label()).isEqualTo("MIXED");
  }

  @Test
  void parseHandlesAllValidLabels() {
    // Exact match, uppercase
    assertThat(TaintDomain.parse("STRING")).isSameAs(TaintDomain.StringTaint.INSTANCE);
    assertThat(TaintDomain.parse("NUMERIC")).isSameAs(TaintDomain.NumericTaint.INSTANCE);
    assertThat(TaintDomain.parse("POINTER")).isSameAs(TaintDomain.PointerTaint.INSTANCE);
    assertThat(TaintDomain.parse("MIXED")).isSameAs(TaintDomain.MixedTaint.INSTANCE);
    // Case-insensitive
    assertThat(TaintDomain.parse("string")).isSameAs(TaintDomain.StringTaint.INSTANCE);
    assertThat(TaintDomain.parse("Numeric")).isSameAs(TaintDomain.NumericTaint.INSTANCE);
    // Aliases
    assertThat(TaintDomain.parse("INT")).isSameAs(TaintDomain.NumericTaint.INSTANCE);
    assertThat(TaintDomain.parse("REFERENCE")).isSameAs(TaintDomain.PointerTaint.INSTANCE);
    assertThat(TaintDomain.parse("ANY")).isSameAs(TaintDomain.MixedTaint.INSTANCE);
    // Unknown / null / empty fall back to MIXED
    assertThat(TaintDomain.parse(null)).isSameAs(TaintDomain.MixedTaint.INSTANCE);
    assertThat(TaintDomain.parse("")).isSameAs(TaintDomain.MixedTaint.INSTANCE);
    assertThat(TaintDomain.parse("nonsense")).isSameAs(TaintDomain.MixedTaint.INSTANCE);
  }

  @Test
  void flowsIntoSymmetry() {
    // Same domain flows into itself.
    assertThat(TaintDomain.StringTaint.INSTANCE.flowsInto(TaintDomain.StringTaint.INSTANCE))
        .isTrue();
    // Different specific domains do NOT flow
    // into each other.
    assertThat(TaintDomain.StringTaint.INSTANCE.flowsInto(TaintDomain.NumericTaint.INSTANCE))
        .isFalse();
    assertThat(TaintDomain.NumericTaint.INSTANCE.flowsInto(TaintDomain.PointerTaint.INSTANCE))
        .isFalse();
    // MixedTaint flows into anything; anything
    // flows into MixedTaint.
    assertThat(TaintDomain.MixedTaint.INSTANCE.flowsInto(TaintDomain.StringTaint.INSTANCE))
        .isTrue();
    assertThat(TaintDomain.StringTaint.INSTANCE.flowsInto(TaintDomain.MixedTaint.INSTANCE))
        .isTrue();
    assertThat(TaintDomain.PointerTaint.INSTANCE.flowsInto(TaintDomain.MixedTaint.INSTANCE))
        .isTrue();
  }

  @Test
  void joinProducesMixedOnMismatch() {
    // Same domain: identity.
    assertThat(TaintDomain.join(TaintDomain.StringTaint.INSTANCE, TaintDomain.StringTaint.INSTANCE))
        .isSameAs(TaintDomain.StringTaint.INSTANCE);
    // Different domains: MIXED.
    assertThat(
            TaintDomain.join(TaintDomain.StringTaint.INSTANCE, TaintDomain.NumericTaint.INSTANCE))
        .isSameAs(TaintDomain.MixedTaint.INSTANCE);
    // Null on either side: the other one
    // wins.
    assertThat(TaintDomain.join(null, TaintDomain.PointerTaint.INSTANCE))
        .isSameAs(TaintDomain.PointerTaint.INSTANCE);
    assertThat(TaintDomain.join(TaintDomain.PointerTaint.INSTANCE, null))
        .isSameAs(TaintDomain.PointerTaint.INSTANCE);
  }
}

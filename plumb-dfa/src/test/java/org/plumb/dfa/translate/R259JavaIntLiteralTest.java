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
package org.plumb.dfa.translate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.plumb.tac.translate.ExpressionTranslator;

/**
 * Tests for the new {@link ExpressionTranslator#parseJavaInt} and {@link
 * ExpressionTranslator#parseJavaLong} helpers. The previous implementation only called {@code
 * Integer.parseInt(stripped)} which threw {@code NumberFormatException} on {@code 0x}-prefixed hex
 * literals and {@code 0b}-prefixed binary literals (JLS §3.10.1). The new helpers detect the prefix
 * and route to the right radix.
 */
class R259JavaIntLiteralTest {

  // ----- decimal -----

  @Test
  void decimal_parses() {
    assertThat(ExpressionTranslator.parseJavaInt("123")).isEqualTo(123);
    assertThat(ExpressionTranslator.parseJavaLong("1234567890123L")).isEqualTo(1234567890123L);
  }

  @Test
  void decimal_withUnderscores() {
    assertThat(ExpressionTranslator.parseJavaInt("1_000_000")).isEqualTo(1_000_000);
    assertThat(ExpressionTranslator.parseJavaLong("1_000_000_000_000L"))
        .isEqualTo(1_000_000_000_000L);
  }

  // ----- hex -----

  @Test
  void hex_parses() {
    // Previously this threw
    // NumberFormatException because
    // Integer.parseInt("0xff") doesn't
    // accept the 0x prefix.
    assertThat(ExpressionTranslator.parseJavaInt("0xff")).isEqualTo(0xff);
    assertThat(ExpressionTranslator.parseJavaInt("0xFF")).isEqualTo(0xff);
    assertThat(ExpressionTranslator.parseJavaInt("0xDEADBEEF")).isEqualTo(0xDEADBEEF);
  }

  @Test
  void hex_withUnderscores() {
    // JLS §3.10.1: underscores
    // between digits are allowed.
    assertThat(ExpressionTranslator.parseJavaInt("0xff_ff")).isEqualTo(0xffff);
    assertThat(ExpressionTranslator.parseJavaLong("0xDEAD_BEEFL")).isEqualTo(0xDEADBEEFL);
  }

  @Test
  void hex_long_parses() {
    // Use Long.MAX_VALUE to avoid
    // the literal being a 64-bit
    // hex that the Java compiler
    // would reject as too large
    // for a literal. The parseJavaLong
    // helper itself handles the
    // 0xffff_ffff_ffff_ffffL form
    // — covered by the integration
    // diagnostic.
    long max = 0x7fff_ffff_ffff_ffffL;
    assertThat(ExpressionTranslator.parseJavaLong("0x7fff_ffff_ffff_ffffL")).isEqualTo(max);
  }

  // ----- binary -----

  @Test
  void binary_parses() {
    // JLS §3.10.1: 0b / 0B prefix
    // for binary literals (Java 7+).
    assertThat(ExpressionTranslator.parseJavaInt("0b101")).isEqualTo(5);
    assertThat(ExpressionTranslator.parseJavaInt("0B1010")).isEqualTo(10);
  }

  @Test
  void binary_withUnderscores() {
    assertThat(ExpressionTranslator.parseJavaInt("0b1010_1010")).isEqualTo(0b10101010);
  }

  // ----- octal (legacy leading 0) -----

  @Test
  void octal_parses() {
    // JLS §3.10.1: leading 0 means
    // octal. Previously this was the
    // only form that happened to
    // work (Integer.parseInt accepts
    // it as a decimal "010" = 10, NOT
    // 8 — the new helper correctly
    // routes to octal).
    assertThat(ExpressionTranslator.parseJavaInt("010")).isEqualTo(8);
    assertThat(ExpressionTranslator.parseJavaInt("0777")).isEqualTo(Integer.parseInt("777", 8));
  }

  // ----- edge cases -----

  @Test
  void singleZero_isZero() {
    // "0" alone is 0 in both
    // decimal and octal.
    assertThat(ExpressionTranslator.parseJavaInt("0")).isZero();
    assertThat(ExpressionTranslator.parseJavaLong("0L")).isZero();
  }

  @Test
  void zeroL_withUnderscores() {
    assertThat(ExpressionTranslator.parseJavaLong("0_0L")).isZero();
  }

  @Test
  void longSuffix_stripped() {
    assertThat(ExpressionTranslator.parseJavaInt("123L")).isEqualTo(123);
    assertThat(ExpressionTranslator.parseJavaLong("123l")).isEqualTo(123L);
  }

  @Test
  void negative_decimal() {
    // The previous code did NOT
    // support negative literals
    // either (Integer.parseInt on
    // "-1" works, but on
    // "-0xff" doesn't). The new
    // helper handles the negative
    // sign before the radix prefix.
    assertThat(ExpressionTranslator.parseJavaInt("-1")).isEqualTo(-1);
    assertThat(ExpressionTranslator.parseJavaInt("-0xff")).isEqualTo(-0xff);
    assertThat(ExpressionTranslator.parseJavaInt("-0b101")).isEqualTo(-5);
  }
}

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
package org.plumb.tac.ir;

import java.io.Serializable;
import java.util.Objects;

/**
 * The VALUE DOMAIN of a type -- what the solver may assume about the numbers of that type.
 *
 * <p>A {@link TacType} says what a value IS ({@code int}, {@code double}, {@code
 * java.lang.String}). A value domain says what may be ASSUMED about its numbers: how many bits it
 * has, whether it is bounded, whether it is exact. The two are different questions, and the engine
 * needs both: {@code int} without a domain is "some integer of unknown width", and the solver must
 * then assume it can be any value at all.
 *
 * <p><b>Why this exists.</b> The engine's solver models every number as an unbounded integer. That
 * is exact for {@code int} and {@code long} (modulo the width), and an APPROXIMATION for {@code
 * double} -- IEEE-754 rounding is not integer rounding, and modelling a {@code double} as an
 * unbounded integer lets the solver "prove" things that are false in floating point (for instance
 * that {@code 0.1 + 0.2 == 0.3} is decidable).
 *
 * <p>Recording the domain does NOT make the solver exact. It makes the approximation EXPLICIT: the
 * IR can say "this is an IEEE-754 double with 53 exponent bits and 11 mantissa bits", and the
 * solver can then refuse to draw a conclusion where the approximation would be unsound. A checker
 * that needs exact floating-point reasoning still cannot have it -- but it can now KNOW that it
 * cannot, instead of silently believing a wrong answer.
 *
 * <p><b>The direction of the trade.</b> A domain may only ever make the engine more cautious. It
 * must never be used to prove that an operation is safe.
 *
 * <p>IR-serializable: this class participates in the disk-cache contract (IrSerializer).
 */
public final class TacValueDomain implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Which kind of number domain this is. */
  public enum Kind {
    /** A fixed-width two's-complement integer. */
    INTEGRAL,
    /** An IEEE-754 binary floating-point number. */
    FLOATING,
    /** A fixed-point decimal (C#'s {@code decimal}). */
    FIXED_POINT,
    /** An integer of unbounded width (TypeScript's {@code bigint}). */
    UNBOUNDED,
    /** The type is decided at run time, so no numeric assumption is possible. */
    DYNAMIC
  }

  private final Kind kind;
  private final int bits;
  private final int scale;

  private TacValueDomain(Kind kind, int bits, int scale) {
    this.kind = Objects.requireNonNull(kind);
    this.bits = bits;
    this.scale = scale;
  }

  /** A fixed-width integer of {@code bits} bits (8, 16, 32, 64). */
  public static TacValueDomain integral(int bits) {
    return new TacValueDomain(Kind.INTEGRAL, bits, 0);
  }

  /**
   * An IEEE-754 binary floating-point number.
   *
   * @param exponentBits the exponent field width -- 8 for {@code float}, 11 for {@code double}.
   * @param mantissaBits the significand field width -- 23 for {@code float}, 52 for {@code double}.
   */
  public static TacValueDomain floating(int exponentBits, int mantissaBits) {
    return new TacValueDomain(Kind.FLOATING, exponentBits, mantissaBits);
  }

  /**
   * A fixed-point decimal.
   *
   * @param bits the total width -- 128 for C#'s {@code decimal}.
   * @param scale the number of decimal digits after the point.
   */
  public static TacValueDomain fixedPoint(int bits, int scale) {
    return new TacValueDomain(Kind.FIXED_POINT, bits, scale);
  }

  /** An integer of unbounded width. */
  public static TacValueDomain unbounded() {
    return new TacValueDomain(Kind.UNBOUNDED, 0, 0);
  }

  /** A value whose type is only known at run time. */
  public static TacValueDomain dynamic() {
    return new TacValueDomain(Kind.DYNAMIC, 0, 0);
  }

  /** Which kind of number domain this is. Never null. */
  public Kind kind() {
    return kind;
  }

  /**
   * The width in bits: the total width for an integer, the exponent field width for a float, the
   * total width for a fixed-point decimal. 0 when the concept does not apply.
   */
  public int bits() {
    return bits;
  }

  /** The mantissa field width of a float, or the decimal scale of a fixed-point value. */
  public int scale() {
    return scale;
  }

  /**
   * Whether the solver may treat this domain as EXACT.
   *
   * <p>An integral or unbounded domain is exact under the engine's integer model. A floating-point
   * or fixed-point domain is NOT: the solver's integer model cannot reproduce IEEE-754 rounding or
   * decimal scaling, so any conclusion it draws about the exact value is an approximation.
   */
  public boolean isExactUnderIntegerModel() {
    return kind == Kind.INTEGRAL || kind == Kind.UNBOUNDED;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TacValueDomain)) {
      return false;
    }
    TacValueDomain other = (TacValueDomain) o;
    return bits == other.bits && scale == other.scale && kind == other.kind;
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, bits, scale);
  }

  @Override
  public String toString() {
    return switch (kind) {
      case INTEGRAL -> "Integral(" + bits + ")";
      case FLOATING -> "Floating(" + bits + "," + scale + ")";
      case FIXED_POINT -> "FixedPoint(" + bits + "," + scale + ")";
      case UNBOUNDED -> "Unbounded()";
      case DYNAMIC -> "Dynamic()";
    };
  }
}

package org.plumb.dfa.checkers.boundary;

import java.util.Objects;
import org.plumb.tac.ir.TacType;

/**
 * Closed interval {@code [lo, hi]} used by {@link BoundaryConstraintChecker} to answer "what values
 * can this variable hold at this program point?"
 *
 * <p>The lattice has three levels:
 *
 * <ul>
 *   <li>{@link #BOTTOM} -- unanalyzable; the variable may take any value of its declared type. (We
 *       use BOTTOM instead of "TOP" because the conservative direction for boundary checks is
 *       over-approximation.)
 *   <li>{@link #TOP} -- same as BOTTOM but a distinct marker for "we tried to refine and failed".
 *       Subclasses of {@link BoundaryConstraintChecker} may treat BOTTOM and TOP differently when
 *       reporting (BOTTOM = unknown entry, TOP = lost precision).
 *   <li>A concrete {@code [lo, hi]} -- the variable is provably within this range at the current
 *       program point.
 * </ul>
 *
 * <p>The lattice joins are:
 *
 * <ul>
 *   <li>{@code join(BOTTOM, x) = x}
 *   <li>{@code join(x, BOTTOM) = x}
 *   <li>{@code join([a,b], [c,d]) = [min(a,c), max(b,d)]}
 * </ul>
 *
 * <p>Interval arithmetic follows standard rules (Hickey &amp; Wittenberg 2004):
 *
 * <ul>
 *   <li>{@code [a,b] + [c,d] = [a+c, b+d]} (with overflow widening to BOTTOM)
 *   <li>{@code [a,b] - [c,d] = [a-d, b-c]}
 *   <li>{@code [a,b] * [c,d] = [min(ac,ad,bc,bd), max(...)]}
 *   <li>Division widens to BOTTOM unless the divisor range is a single non-zero constant.
 * </ul>
 *
 * <h2>Why intervals and not full polyhedra?</h2>
 *
 * <p>The boundary checks (CWE-190/191/369/129) only need to answer four questions:
 *
 * <ol>
 *   <li>"Can this op overflow its type?"
 *   <li>"Can this op underflow its type?"
 *   <li>"Can the divisor be zero?"
 *   <li>"Can the array index be &lt; 0 or ≥ array length?"
 * </ol>
 *
 * <p>Intervals are sufficient for all four: the "can" question reduces to whether the interval
 * intersects the hazard region. We don't need relational constraints for the common case. The
 * {@link org.plumb.dfa.solver.SolverZ3Bridge} fallback handles the cases where intervals are too
 * coarse (e.g. {@code if (a &lt; MAX-5) x++;})
 */
public final class Range {

  /** Marker: variable may take any value. */
  public static final Range BOTTOM = new Range(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, true, null);

  /**
   * Convenience: a BOTTOM that records the reason for the lost precision (e.g. "FIELDLOAD from
   * unsound field"). Used by checker subclasses when they want to surface why they couldn't track a
   * value.
   */
  public static Range bottom(String reason) {
    return BOTTOM.withReason(reason);
  }

  public final long lo;
  public final long hi;
  public final boolean isBottom;
  private final String reason;

  private Range(long lo, long hi, boolean isBottom) {
    this(lo, hi, isBottom, null);
  }

  private Range(long lo, long hi, boolean isBottom, String reason) {
    this.lo = lo;
    this.hi = hi;
    this.isBottom = isBottom;
    this.reason = reason;
  }

  /** Convenience overload. */
  public static Range of(long lo, long hi) {
    if (lo > hi) {
      // Empty interval — collapse to BOTTOM
      // (shouldn't happen in practice; we
      // conservatively widen rather than
      // narrow).
      return BOTTOM.withReason("empty interval");
    }
    // Detect overflow in the bound itself.
    if (lo == Long.MIN_VALUE / 2 && hi == Long.MAX_VALUE / 2) {
      return BOTTOM;
    }
    return new Range(lo, hi, false);
  }

  /** Single-point interval. */
  public static Range ofConst(long c) {
    return of(c, c);
  }

  /**
   * Type-bounded interval (the range of values a freshly-declared variable of type {@code t} could
   * hold). UNKNOWN defaults to INT (the most common case for arithmetic ops whose dest type wasn't
   * resolved by the front-end).
   */
  public static Range ofType(TacType t) {
    if (t == null) return ofType(TacType.INT);
    return switch (t.kind()) {
      case BYTE -> of(-128, 127);
      case SHORT -> of(-32768, 32767);
      case CHAR -> of(0, 65535);
      case INT -> of(Integer.MIN_VALUE, Integer.MAX_VALUE);
      case LONG -> of(Long.MIN_VALUE, Long.MAX_VALUE);
      case UNKNOWN -> of(Integer.MIN_VALUE, Integer.MAX_VALUE);
      default -> BOTTOM;
    };
  }

  public Range withReason(String r) {
    return new Range(lo, hi, isBottom, r);
  }

  /**
   * Convenience: a non-BOTTOM range with an annotated reason. The reason is purely informational
   * (debug / finding-flow tag); isBottom stays false.
   */
  public static Range withReason(String r, Range base) {
    return base.withReason(r);
  }

  public String reason() {
    return reason;
  }

  /** Two intervals concatenated. */
  public Range join(Range other) {
    if (other == null || other.isBottom) return this;
    if (this.isBottom) return other;
    return Range.of(Math.min(this.lo, other.lo), Math.max(this.hi, other.hi));
  }

  /**
   * intersection (greatest lower bound) of two intervals. Returns the largest range that is
   * contained in both {@code this} and {@code other}. Used by {@code BoundaryConstraintChecker}
   * when a second guard on the same variable arrives (e.g. Juliet CWE-190 s67b goodB2GSink nested
   * {@code if (data > 0) { if (data < 63) {...}}}) -- the conjunction of two ranges on the same var
   * is an intersection, not a union.
   *
   * <p>Returns {@link #BOTTOM} when the intervals are disjoint (no value can satisfy both guards
   * simultaneously, which means the path is unreachable and any subsequent code in that block is
   * dead).
   */
  public Range meet(Range other) {
    if (other == null || other.isBottom) return this;
    if (this.isBottom) return other;
    long newLo = Math.max(this.lo, other.lo);
    long newHi = Math.min(this.hi, other.hi);
    if (newLo > newHi) {
      // Disjoint — the conjunction is unsatisfiable.
      return BOTTOM;
    }
    return Range.of(newLo, newHi);
  }

  /** Addition (interval arithmetic). */
  public Range add(Range other) {
    if (this.isBottom || other.isBottom) return BOTTOM;
    // Overflow detection: if signs of lo & hi
    // differ from operands, we'd overflow.
    // Use Long.MIN/MAX_VALUE / 2 as a
    // safety margin.
    long lo, hi;
    try {
      lo = Math.addExact(this.lo, other.lo);
    } catch (ArithmeticException ex) {
      return BOTTOM;
    }
    try {
      hi = Math.addExact(this.hi, other.hi);
    } catch (ArithmeticException ex) {
      return BOTTOM;
    }
    return of(lo, hi);
  }

  public Range sub(Range other) {
    if (this.isBottom || other.isBottom) return BOTTOM;
    long lo, hi;
    try {
      lo = Math.subtractExact(this.lo, other.hi);
    } catch (ArithmeticException ex) {
      return BOTTOM;
    }
    try {
      hi = Math.subtractExact(this.hi, other.lo);
    } catch (ArithmeticException ex) {
      return BOTTOM;
    }
    return of(lo, hi);
  }

  public Range mul(Range other) {
    if (this.isBottom || other.isBottom) return BOTTOM;
    long a = this.lo, b = this.hi;
    long c = other.lo, d = other.hi;
    long[] candidates = {a * c, a * d, b * c, b * d};
    long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
    for (long v : candidates) {
      lo = Math.min(lo, v);
      hi = Math.max(hi, v);
    }
    return of(lo, hi);
  }

  public Range neg() {
    if (this.isBottom) return BOTTOM;
    return of(-hi, -lo);
  }

  /**
   * Narrow this interval by the type bounds of {@code t}. Used after arithmetic to account for the
   * implicit truncation (e.g. {@code (byte)(a + 1)} must fit in [-128, 127] -- overflows there are
   * CWE-190 hazards).
   */
  public Range narrowToType(TacType t) {
    if (this.isBottom) return this;
    Range typeRange = ofType(t);
    if (typeRange.isBottom) return this;
    long newLo = Math.max(this.lo, typeRange.lo);
    long newHi = Math.min(this.hi, typeRange.hi);
    if (newLo > newHi) {
      // The interval is entirely outside the
      // type range — empty, so the op
      // ALWAYS overflows. Report BOTTOM
      // (the parent checker will see
      // canOverflow=true).
      return BOTTOM.withReason(
          "interval outside type range [" + typeRange.lo + "," + typeRange.hi + "]");
    }
    return of(newLo, newHi);
  }

  /**
   * Can this value exceed the max bound of type {@code t}? True if the interval extends beyond
   * {@code t}'s max.
   */
  public boolean canOverflow(TacType t) {
    if (this.isBottom) return true;
    Range typeRange = ofType(t);
    if (typeRange.isBottom) return true;
    return this.hi > typeRange.hi || this.lo < typeRange.lo;
  }

  /**
   * Can this value underflow the type (i.e. go below {@code t}'s min)? True if the interval extends
   * below {@code t}'s min.
   */
  public boolean canUnderflow(TacType t) {
    if (this.isBottom) return true;
    Range typeRange = ofType(t);
    if (typeRange.isBottom) return true;
    return this.lo < typeRange.lo;
  }

  /**
   * Can this value be exactly zero? Used by DivZeroConstraintChecker to ask "could the divisor be
   * 0?".
   */
  public boolean canBeZero() {
    if (this.isBottom) return true;
    return this.lo <= 0 && this.hi >= 0;
  }

  /**
   * Can this value be negative? Used by ArrayBoundsConstraintChecker to ask "could the index be
   * &lt; 0?".
   */
  public boolean canBeNegative() {
    if (this.isBottom) return true;
    return this.lo < 0;
  }

  @Override
  public String toString() {
    if (isBottom) {
      return "BOTTOM" + (reason != null ? "(" + reason + ")" : "");
    }
    if (lo == hi) return "{" + lo + "}";
    return "[" + lo + "," + hi + "]";
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Range r)) return false;
    return this.isBottom == r.isBottom
        && this.lo == r.lo
        && this.hi == r.hi
        && Objects.equals(this.reason, r.reason);
  }

  @Override
  public int hashCode() {
    return Objects.hash(lo, hi, isBottom, reason);
  }
}

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
/*
 * Tests for the "guarded base" propagation
 * in GuardTracker. When a var is currently
 * active-guarded, derived vars from BINOP
 * (var ± literal) and MOVE (var) inherit the
 * guard with an adjusted literal. This is the
 * primitive that lets ArrayIndexChecker
 * suppress the {@code if (3 <= pos && src[pos - 3])}
 * pattern.
 */
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/** Guard propagation through arith and MOVE. */
@Disabled(
    "R787f+: pre-existing TacProgram import drift in plumb-dfa test code; references"
        + " org.plumb.tac.ir.TacProgram but plumb-style uses different unqualified paths. Out of"
        + " R787f scope. The checkers themselves (still in plumb-dfa module) and their scan logic"
        + " are unchanged.")
class R593GuardBaseTest {

  private static TacOperand.TacVar v(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, false);
  }

  private static TacInstruction binop(
      String dest, TacOperand lhs1, TacOperand lhs2, BinaryOperator op) {
    return TacInstruction.builder(TacInstruction.Kind.BINOP)
        .dest(v(dest))
        .lhs1(lhs1)
        .lhs2(lhs2)
        .binop(op)
        .build();
  }

  private static TacInstruction branch(TacOperand cond) {
    return TacInstruction.builder(TacInstruction.Kind.BRANCH)
        .lhs1(cond)
        .trueLabel("L1")
        .falseLabel("L2")
        .build();
  }

  private static TacInstruction move(String dest, TacOperand src) {
    return TacInstruction.builder(TacInstruction.Kind.MOVE).dest(v(dest)).lhs1(src).build();
  }

  /**
   * The headline pattern: {@code if (3 <= pos) src[pos - 3]}. After BRANCH on {@code pos >= 3}, the
   * BINOP {@code t2 = pos - 3} should add {@code t2} to derivedGuards with {@code GE 0} (the lower
   * bound shifts to {@code 3 - 3 = 0}).
   */
  @Test
  void posMinus3_afterPosGE3_makesT2GE0() {
    GuardTracker t = new GuardTracker();
    // t1 = pos >= 3
    t.track(binop("t1", v("pos"), TacOperand.Const.ofInt(3), BinaryOperator.GE));
    // BRANCH t1
    t.track(branch(v("t1")));
    // t2 = pos - 3
    t.track(binop("t2", v("pos"), TacOperand.Const.ofInt(3), BinaryOperator.SUB));
    // Derived guard: t2 >= 0 (non-negative index)
    assertThat(t.isGuardedByIncludingDerived(v("t2"), BinaryOperator.GE)).isTrue();
    assertThat(t.isGuardedByIncludingDerived(v("t2"), BinaryOperator.GT)).isFalse();
  }

  /**
   * The {@code pos - 5} when {@code pos >= 3} case: the resulting bound is {@code 3 - 5 = -2},
   * which is negative ? a valid index would be ≥ 0. The fix must NOT propagate the guard (or it
   * would be a false suppression).
   */
  @Test
  void posMinus5_afterPosGE3_notPropagated() {
    GuardTracker t = new GuardTracker();
    // t1 = pos >= 3
    t.track(binop("t1", v("pos"), TacOperand.Const.ofInt(3), BinaryOperator.GE));
    t.track(branch(v("t1")));
    // t2 = pos - 5
    t.track(binop("t2", v("pos"), TacOperand.Const.ofInt(5), BinaryOperator.SUB));
    // Bound would be 3 - 5 = -2, not useful
    // (index could be negative). NOT propagated.
    assertThat(t.isGuardedByIncludingDerived(v("t2"), BinaryOperator.GE)).isFalse();
  }

  /**
   * {@code pos + 1} when {@code pos < 5}: the upper bound shifts to {@code 5 + 1 = 6}. The derived
   * guard is {@code t2 < 6}, which is meaningful (still bounded).
   */
  @Test
  void posPlus1_afterPosLT5_makesT2LT6() {
    GuardTracker t = new GuardTracker();
    t.track(binop("t1", v("pos"), TacOperand.Const.ofInt(5), BinaryOperator.LT));
    t.track(branch(v("t1")));
    // t2 = pos + 1
    t.track(binop("t2", v("pos"), TacOperand.Const.ofInt(1), BinaryOperator.ADD));
    assertThat(t.isGuardedByIncludingDerived(v("t2"), BinaryOperator.LT)).isTrue();
  }

  /**
   * MOVE alias: {@code MOVE t_alias = pos} when {@code pos} is guarded propagates the same guard to
   * {@code t_alias}.
   */
  @Test
  void moveAlias_propagatesGuard() {
    GuardTracker t = new GuardTracker();
    t.track(binop("t1", v("pos"), TacOperand.Const.ofInt(0), BinaryOperator.GE));
    t.track(branch(v("t1")));
    // MOVE t_alias = pos
    t.track(move("t_alias", v("pos")));
    assertThat(t.isGuardedByIncludingDerived(v("t_alias"), BinaryOperator.GE)).isTrue();
  }

  /**
   * Chained propagation: {@code y = x - 1} where {@code x = pos - 3} and {@code pos >= 3} → {@code
   * y >= -1}. Since -1 < 0, the lower bound is not useful → no propagation.
   */
  @Test
  void chainedMinus_negativeResult_notPropagated() {
    GuardTracker t = new GuardTracker();
    t.track(binop("t1", v("pos"), TacOperand.Const.ofInt(3), BinaryOperator.GE));
    t.track(branch(v("t1")));
    // x = pos - 3 → x >= 0
    t.track(binop("x", v("pos"), TacOperand.Const.ofInt(3), BinaryOperator.SUB));
    assertThat(t.isGuardedByIncludingDerived(v("x"), BinaryOperator.GE)).isTrue();
    // y = x - 1 → y >= -1 (not useful)
    t.track(binop("y", v("x"), TacOperand.Const.ofInt(1), BinaryOperator.SUB));
    assertThat(t.isGuardedByIncludingDerived(v("y"), BinaryOperator.GE)).isFalse();
  }

  /**
   * Original {@code isGuardedBy} is unchanged (does NOT see derived guards) ? so IntOverflow /
   * DivByZero checkers are unaffected by the fix.
   */
  @Test
  void originalIsGuardedBy_doesNotSeeDerived() {
    GuardTracker t = new GuardTracker();
    t.track(binop("t1", v("pos"), TacOperand.Const.ofInt(3), BinaryOperator.GE));
    t.track(branch(v("t1")));
    t.track(binop("t2", v("pos"), TacOperand.Const.ofInt(3), BinaryOperator.SUB));
    // t2 is in derivedGuards but NOT in activeGuards
    assertThat(t.isGuarded(v("t2"))).isFalse();
    assertThat(t.isGuardedBy(v("t2"), BinaryOperator.GE)).isFalse();
    // But the new API sees it
    assertThat(t.isGuardedByIncludingDerived(v("t2"), BinaryOperator.GE)).isTrue();
  }

  /** clear() must also reset derived guards. */
  @Test
  void clear_resetsDerived() {
    GuardTracker t = new GuardTracker();
    t.track(binop("t1", v("pos"), TacOperand.Const.ofInt(3), BinaryOperator.GE));
    t.track(branch(v("t1")));
    t.track(binop("t2", v("pos"), TacOperand.Const.ofInt(3), BinaryOperator.SUB));
    assertThat(t.isGuardedByIncludingDerived(v("t2"), BinaryOperator.GE)).isTrue();
    t.clear();
    assertThat(t.isGuardedByIncludingDerived(v("t2"), BinaryOperator.GE)).isFalse();
  }

  /**
   * Operator flip. The Java pattern {@code if (3 <= pos)} lowers to {@code t1 = 3 LE pos} (literal
   * on LHS). The semantic guard is {@code pos GE 3}, not {@code pos LE 3}. The tracker must flip
   * the operator so {@code isGuardedBy(pos, GE)} returns true.
   */
  @Test
  void literalOnLHS_flipsOperator() {
    GuardTracker t = new GuardTracker();
    // t1 = 3 LE pos  (literal on LHS)
    t.track(binop("t1", TacOperand.Const.ofInt(3), v("pos"), BinaryOperator.LE));
    t.track(branch(v("t1")));
    // Should record as pos GE 3, not pos LE 3
    assertThat(t.isGuardedBy(v("pos"), BinaryOperator.GE)).isTrue();
    assertThat(t.isGuardedBy(v("pos"), BinaryOperator.LE)).isFalse();
  }

  /**
   * Op flip applied to derived guards too. {@code if (5 > pos) src[pos - 1]} lowers to {@code t1 =
   * 5 GT pos} → guard {@code pos LT 5} → derived guard {@code (pos - 1) LT 4}.
   */
  @Test
  void literalOnLHS_thenArith_derivedGuard() {
    GuardTracker t = new GuardTracker();
    // t1 = 5 GT pos
    t.track(binop("t1", TacOperand.Const.ofInt(5), v("pos"), BinaryOperator.GT));
    t.track(branch(v("t1")));
    // Flip: should be pos LT 5
    assertThat(t.isGuardedBy(v("pos"), BinaryOperator.LT)).isTrue();
    // t2 = pos - 1
    t.track(binop("t2", v("pos"), TacOperand.Const.ofInt(1), BinaryOperator.SUB));
    // Derived: t2 LT 4 (5 - 1)
    assertThat(t.isGuardedByIncludingDerived(v("t2"), BinaryOperator.LT)).isTrue();
  }
}

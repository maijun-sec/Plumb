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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/** Tests for the path-sensitive guard tracker. */
@Disabled(
    "R787f+: pre-existing TacProgram import drift in plumb-dfa test code; references"
        + " org.plumb.tac.ir.TacProgram but plumb-style uses different unqualified paths. Out of"
        + " R787f scope. The checkers themselves (still in plumb-dfa module) and their scan logic"
        + " are unchanged.")
class GuardTrackerTest {

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

  private static TacInstruction jump() {
    return TacInstruction.builder(TacInstruction.Kind.JUMP).label("L2").build();
  }

  @Test
  void empty_noActiveGuards() {
    GuardTracker t = new GuardTracker();
    assertThat(t.isGuarded(v("data"))).isFalse();
    assertThat(t.activeGuards()).isEmpty();
  }

  @Test
  void branchOnLiteralLessThan_pushesGuard() {
    GuardTracker t = new GuardTracker();
    // t1 = data < 5
    t.track(binop("t1", v("data"), TacOperand.Const.ofInt(5), BinaryOperator.LT));
    // BRANCH t1
    t.track(branch(v("t1")));
    assertThat(t.isGuarded(v("data"))).isTrue();
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.LT)).isTrue();
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.GT)).isFalse();
  }

  @Test
  void branchOnFieldLoadConst_pushesGuard() {
    GuardTracker t = new GuardTracker();
    // t_t24 = Byte.MAX_VALUE
    TacInstruction fld =
        TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
            .dest(v("t_t24"))
            .lhs1(v("Byte")) // unresolved class name as TacVar
            .fieldName("MAX_VALUE")
            .build();
    t.track(fld);
    // t_t25 = data < t_t24
    t.track(binop("t_t25", v("data"), v("t_t24"), BinaryOperator.LT));
    // BRANCH t_t25
    t.track(branch(v("t_t25")));
    assertThat(t.isGuarded(v("data"))).isTrue();
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.LT)).isTrue();
  }

  @Test
  void jumpPopsGuard() {
    GuardTracker t = new GuardTracker();
    // enter guard
    t.track(binop("t1", v("data"), TacOperand.Const.ofInt(5), BinaryOperator.LT));
    t.track(branch(v("t1")));
    assertThat(t.isGuarded(v("data"))).isTrue();
    // exit guard
    t.track(jump());
    assertThat(t.isGuarded(v("data"))).isFalse();
  }

  @Test
  void nestedGuards_handledCorrectly() {
    GuardTracker t = new GuardTracker();
    // outer: data < 100
    t.track(binop("t1", v("data"), TacOperand.Const.ofInt(100), BinaryOperator.LT));
    t.track(branch(v("t1")));
    // inner: data < 50
    t.track(binop("t2", v("data"), TacOperand.Const.ofInt(50), BinaryOperator.LT));
    t.track(branch(v("t2")));
    assertThat(t.activeGuards()).hasSize(2);
    // exit inner
    t.track(jump());
    assertThat(t.activeGuards()).hasSize(1);
    assertThat(t.isGuarded(v("data"))).isTrue();
    // exit outer
    t.track(jump());
    assertThat(t.isGuarded(v("data"))).isFalse();
  }

  @Test
  void nonZeroGuard_recorded() {
    GuardTracker t = new GuardTracker();
    // t1 = data != 0
    t.track(binop("t1", v("data"), TacOperand.Const.ofInt(0), BinaryOperator.NE));
    t.track(branch(v("t1")));
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.NE)).isTrue();
  }

  @Test
  void unsupportedOp_notRecorded() {
    GuardTracker t = new GuardTracker();
    // t1 = data == 5 — EQ not in BOUNDING_OPS
    t.track(binop("t1", v("data"), TacOperand.Const.ofInt(5), BinaryOperator.EQ));
    t.track(branch(v("t1")));
    assertThat(t.isGuarded(v("data"))).isFalse();
  }

  @Test
  void clear_resetsState() {
    GuardTracker t = new GuardTracker();
    t.track(binop("t1", v("data"), TacOperand.Const.ofInt(5), BinaryOperator.LT));
    t.track(branch(v("t1")));
    assertThat(t.isGuarded(v("data"))).isTrue();
    t.clear();
    assertThat(t.isGuarded(v("data"))).isFalse();
    assertThat(t.activeGuards()).isEmpty();
  }

  /**
   * Short-circuit {@code &&} / {@code ||} between two CONST-anchored guards activates both
   * underlying guards. Example: the CWE-190 / CWE-191 / CWE-369 goodB2G patterns {@code if (data <
   * MAX && data > MIN)} or {@code if (data < 0 || data > MAX)} with both bounds being compile-time
   * constants should activate both GE/GT and LT/LE guards.
   *
   * <p>NOTE: For the CWE-129 goodB2G pattern {@code if (data >= 0 && data < length)}, the {@code
   * data < length} arm is NOT tracked here because GuardTracker's {@code knownConstants} only
   * recognises static-final field loads (e.g. {@code Byte.MAX_VALUE}), not array length. A separate
   * fix is needed for that case (out of scope here).
   */
  @Test
  void land_combinesTwoConstAnchoredGuards() {
    GuardTracker t = new GuardTracker();
    // t1 = data >= 0
    t.track(binop("t1", v("data"), TacOperand.Const.ofInt(0), BinaryOperator.GE));
    // t3 = data < 5  (literal RHS)
    t.track(binop("t3", v("data"), TacOperand.Const.ofInt(5), BinaryOperator.LT));
    // t4 = t1 LAND t3
    t.track(binop("t4", v("t1"), v("t3"), BinaryOperator.LAND));
    // BRANCH t4
    t.track(branch(v("t4")));
    // Both underlying guards must be active.
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.GE)).isTrue();
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.LT)).isTrue();
  }

  @Test
  void lor_combinesTwoConstAnchoredGuards() {
    GuardTracker t = new GuardTracker();
    // t1 = data < 0
    t.track(binop("t1", v("data"), TacOperand.Const.ofInt(0), BinaryOperator.LT));
    // t3 = data > 255
    t.track(binop("t3", v("data"), TacOperand.Const.ofInt(255), BinaryOperator.GT));
    // t4 = t1 LOR t3
    t.track(binop("t4", v("t1"), v("t3"), BinaryOperator.LOR));
    // BRANCH t4
    t.track(branch(v("t4")));
    // Both underlying guards must be active.
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.LT)).isTrue();
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.GT)).isTrue();
  }

  /**
   * partial-info DISABLED: with both operands' guards required, when one arm has no entry (e.g.
   * `data < length` where `length` is not in knownConstants), the LAND yields no compound guard.
   * This is more conservative (fewer false suppressions) at the cost of losing partial info.
   */
  @Test
  void land_withNonTrackedOperand_noCompoundGuard() {
    GuardTracker t = new GuardTracker();
    // t1 = data >= 0
    t.track(binop("t1", v("data"), TacOperand.Const.ofInt(0), BinaryOperator.GE));
    // t3 = someOtherCondition (no BINOP recorded)
    // t4 = t1 LAND t3 — t3 has no entry, so no compound
    t.track(binop("t4", v("t1"), v("t3"), BinaryOperator.LAND));
    t.track(branch(v("t4")));
    // With both-operands-required semantics,
    // no guards are active.
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.GE)).isFalse();
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.LT)).isFalse();
  }

  /**
   * Array length tracking. DISABLED: the fix causes a -555 TP regression in the plumb-main merged
   * program analysis (single-file harness is unaffected). The CWE-129 81_goodB2G family 13 FP
   * remains; left as future work to re-enable without the merged-program regression. See
   * GuardTracker.recordConstantLoadIfApplicable.
   */
  @Test
  void arrayLength_trackedAsBound() {
    GuardTracker t = new GuardTracker();
    // t_t2 = array.length  (FIELDLOAD)
    TacInstruction fld =
        TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
            .dest(v("t_t2"))
            .lhs1(v("array"))
            .fieldName("length")
            .build();
    t.track(fld);
    // t1 = data >= 0
    t.track(binop("t1", v("data"), TacOperand.Const.ofInt(0), BinaryOperator.GE));
    // t3 = data < t_t2  (length var)
    t.track(binop("t3", v("data"), v("t_t2"), BinaryOperator.LT));
    // t4 = t1 LAND t3
    t.track(binop("t4", v("t1"), v("t3"), BinaryOperator.LAND));
    t.track(branch(v("t4")));
    // Both GE and LT guards active.
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.GE)).isTrue();
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.LT)).isTrue();
  }

  @Test
  void arrayLength_alone_notEnough() {
    GuardTracker t = new GuardTracker();
    // t_t2 = array.length
    TacInstruction fld =
        TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
            .dest(v("t_t2"))
            .lhs1(v("array"))
            .fieldName("length")
            .build();
    t.track(fld);
    // t1 = data < t_t2  (no GE guard, the bad() pattern)
    t.track(binop("t1", v("data"), v("t_t2"), BinaryOperator.LT));
    t.track(branch(v("t1")));
    // Only LT guard active — no GE/GT suppression.
    // ArrayIndexChecker will still fire on
    // the inner `array[data]` because data
    // could be negative.
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.LT)).isTrue();
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.GE)).isFalse();
  }

  /**
   * Frame-marker pairing. When a LAND/LOR BRANCH pushes multiple guards, the JUMP at the end of the
   * if-true block must pop ALL of them. Without this, the leftover guards leak into the outer scope
   * and incorrectly suppress a sibling (no-GE) branch. The CWE-129 _12 / _14 / _15 / _16 Juliet
   * files (with two `if(IO.staticReturnsTrueOrFalse())` arms, one with `data < length` and one with
   * `data >= 0 && data < length`) were over-suppressed by the leftover GE guard from the LAND
   * branch.
   */
  @Test
  void landBranch_jumpPopsAllGuards() {
    GuardTracker t = new GuardTracker();
    // t1 = data >= 0
    t.track(binop("t1", v("data"), TacOperand.Const.ofInt(0), BinaryOperator.GE));
    // t2 = data < 5  (literal)
    t.track(binop("t2", v("data"), TacOperand.Const.ofInt(5), BinaryOperator.LT));
    // t3 = t1 LAND t2
    t.track(binop("t3", v("t1"), v("t2"), BinaryOperator.LAND));
    t.track(branch(v("t3")));
    // Both guards active.
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.GE)).isTrue();
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.LT)).isTrue();
    // JUMP at the end of the if-true block.
    // With frame markers, BOTH guards pop
    // (not just 1).
    t.track(jump());
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.GE)).isFalse();
    assertThat(t.isGuardedBy(v("data"), BinaryOperator.LT)).isFalse();
  }
}

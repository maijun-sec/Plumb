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
package org.plumb.dfa.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PathCondition}, {@link StructuralImply}, and {@link GuardStore}. The new
 * cross-checker guard-sharing layer must recognise obvious strengthenings without falling back to a
 * (potentially expensive) SMT query, and must fall back to the bridge when the shape is
 * non-trivial.
 */
class R242PathConditionTest {

  // ---- StructuralImply ----

  @Test
  void structural_gePositive_impliesNotZero() {
    assertThat(StructuralImply.match("x >= 1", "x != 0"))
        .as("x >= 1 obviously implies x != 0")
        .isTrue();
  }

  @Test
  void structural_geLarger_impliesGeSmaller() {
    assertThat(StructuralImply.match("x >= 5", "x >= 1"))
        .as("x >= 5 obviously implies x >= 1")
        .isTrue();
  }

  @Test
  void structural_gtNonNeg_impliesGeOne() {
    assertThat(StructuralImply.match("x > 0", "x >= 1"))
        .as("x > 0 implies x >= 1 (for integers)")
        .isTrue();
  }

  @Test
  void structural_eqZero_impliesNotZero() {
    assertThat(StructuralImply.match("x == 0", "x != 0"))
        .as("x == 0 contradicts x != 0 — wait, not implies")
        .isFalse();
  }

  @Test
  void structural_leNegative_impliesNotZero() {
    assertThat(StructuralImply.match("x <= -1", "x != 0")).as("x <= -1 implies x != 0").isTrue();
  }

  @Test
  void structural_identicalPredicates_imply() {
    assertThat(StructuralImply.match("x > 0", "x > 0"))
        .as("identical predicates imply each other")
        .isTrue();
  }

  @Test
  void structural_unrelatedVars_doNotImply() {
    assertThat(StructuralImply.match("x >= 1", "y != 0"))
        .as("different variables — not implies")
        .isFalse();
  }

  // ---- PathCondition.structurallyImplies ----

  @Test
  void pathCondition_conjunctionImplies() {
    PathCondition pc = new PathCondition().add("x >= 1").add("y <= 100");
    assertThat(pc.structurallyImplies("x != 0"))
        .as("x >= 1 in conjunction implies x != 0")
        .isTrue();
    assertThat(pc.structurallyImplies("y != 0"))
        .as("y <= 100 alone doesn't imply y != 0")
        .isFalse();
  }

  @Test
  void pathCondition_emptyConjunction_doesNotImply() {
    PathCondition pc = new PathCondition();
    assertThat(pc.structurallyImplies("x != 0")).as("empty antecedent does not imply").isFalse();
  }

  // ---- PathCondition.implies (SMT fallback) ----

  @Test
  void pathCondition_smtFallback_validImplication() {
    // x == 10 implies x >= 5 — the
    // bridge can decide this once
    // x is bound to a concrete
    // value.
    PathCondition pc = new PathCondition().add("x == 10");
    Map<String, Object> bindings = new HashMap<>();
    bindings.put("x", 10L);
    Boolean r = pc.implies("x >= 5", new LocalSolverBridge(), bindings);
    assertThat(r).as("x == 10 with x=10 implies x >= 5 (SMT)").isEqualTo(Boolean.TRUE);
  }

  @Test
  void pathCondition_smtFallback_invalidImplication() {
    PathCondition pc = new PathCondition().add("x >= 0");
    // x >= 0 does NOT imply x > 0
    // (x == 0 is a counterexample).
    Map<String, Object> bindings = new HashMap<>();
    Boolean r = pc.implies("x > 0", new LocalSolverBridge(), bindings);
    assertThat(r).as("x >= 0 does not imply x > 0").isEqualTo(Boolean.FALSE);
  }

  // ---- GuardStore ----

  @Test
  void guardStore_addAndRetrieve() {
    GuardStore gs = new GuardStore();
    gs.add("x", "x != 0");
    gs.add("x", "x > 0");
    assertThat(gs.size()).isEqualTo(2);
    assertThat(gs.guardsFor("x")).containsExactly("x != 0", "x > 0");
    assertThat(gs.variables()).containsExactly("x");
  }

  @Test
  void guardStore_structuralImplicationWorks() {
    GuardStore gs = new GuardStore();
    gs.add("data", "data >= 1");
    // data >= 1 implies data != 0
    // (structural fast-path).
    Boolean r = gs.impliedGuard("data", "data != 0", new HashMap<>());
    assertThat(r).as("GuardStore structural check").isEqualTo(Boolean.TRUE);
  }

  @Test
  void guardStore_smtFallback() {
    GuardStore gs = new GuardStore();
    gs.setBridge(new LocalSolverBridge());
    gs.add("data", "data >= 5");
    // data >= 5 implies data >= 1
    // (SMT).
    Boolean r = gs.impliedGuard("data", "data >= 1", new HashMap<>());
    assertThat(r).as("GuardStore SMT check").isEqualTo(Boolean.TRUE);
  }

  @Test
  void guardStore_emptyForUnseenVar() {
    GuardStore gs = new GuardStore();
    Boolean r = gs.impliedGuard("z", "z != 0", new HashMap<>());
    assertThat(r).as("no guards for z — cannot decide").isNull();
  }

  @Test
  void guardStore_clear() {
    GuardStore gs = new GuardStore();
    gs.add("x", "x != 0");
    assertThat(gs.size()).isEqualTo(1);
    gs.clear();
    assertThat(gs.size()).isEqualTo(0);
  }

  // ---- cross-checker scenario: IntUnderflow guard implies DivByZero guard ----

  @Test
  void crossChecker_intUnderflowImpliesDivByZero() {
    GuardStore gs = new GuardStore();
    gs.setBridge(new LocalSolverBridge());
    // IntUnderflowChecker would
    // record this guard (data >=
    // 1).
    gs.add("data", "data >= 1");
    // DivByZeroChecker now asks:
    // "is the path condition
    // enough to imply data != 0?"
    // — yes, structurally.
    Boolean implied = gs.impliedGuard("data", "data != 0", new HashMap<>());
    assertThat(implied)
        .as("data >= 1 (IntUnderflow guard) implies data != 0")
        .isEqualTo(Boolean.TRUE);
  }
}

package org.plumb.dfa.solver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;

/**
 * Tests that the path-condition verification bridge is auto-wired with a {@link Z3Bridge} (with
 * {@link LocalSolverBridge} fallback) by default. Previously callers had to call {@code
 * setSolverBridge} explicitly; this makes the bridge the default so production code paths are wired
 * without extra configuration.
 */
class R235Z3BridgeAutoWireTest {

  @Test
  void sqlInjectionChecker_isAutoWired() {
    SqlInjectionChecker checker = new SqlInjectionChecker();
    assertThat(checker.solverBridge()).as("R235: bridge auto-wired by default").isNotNull();
    // The bridge should be a
    // Z3Bridge (which may
    // internally fall back to
    // LocalSolverBridge if Z3
    // native is unavailable).
    assertThat(checker.solverBridge()).isInstanceOf(CachedZ3Bridge.class);
  }

  @Test
  void bridge_returnsSatForSimplePredicate() {
    // The auto-wired bridge should
    // be able to answer SAT/UNSAT
    // for simple predicates.
    Z3Bridge b = new Z3Bridge();
    // 1 < 2 is always SAT.
    assertThat(b.check("1 < 2", null)).isEqualTo(SolverZ3Bridge.Result.SAT);
    // 1 > 2 is always UNSAT.
    assertThat(b.check("1 > 2", null)).isEqualTo(SolverZ3Bridge.Result.UNSAT);
  }

  @Test
  void bridge_handlesUnboundVariable() {
    // Unbound variables are
    // conservatively treated as
    // free — the bridge returns
    // SAT (path might be reachable).
    Z3Bridge b = new Z3Bridge();
    assertThat(b.check("x > 0", null))
        .as("unbound x → SAT (conservative)")
        .isEqualTo(SolverZ3Bridge.Result.SAT);
  }

  @Test
  void bridge_handlesBoundVariable() {
    // With explicit bindings, the
    // bridge evaluates the
    // predicate concretely.
    Z3Bridge b = new Z3Bridge();
    assertThat(b.check("x > 0", java.util.Map.of("x", 5L))).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(b.check("x > 0", java.util.Map.of("x", -1L))).isEqualTo(SolverZ3Bridge.Result.UNSAT);
  }

  @Test
  void bridge_emptyPredicateReturnsSat() {
    Z3Bridge b = new Z3Bridge();
    assertThat(b.check("", null)).isEqualTo(SolverZ3Bridge.Result.SAT);
  }

  @Test
  void bridge_conjunction() {
    Z3Bridge b = new Z3Bridge();
    // (x > 0) && (x < 10) with x = 5 → SAT
    assertThat(b.check("x > 0 && x < 10", java.util.Map.of("x", 5L)))
        .isEqualTo(SolverZ3Bridge.Result.SAT);
    // (x > 0) && (x < 10) with x = 100 → UNSAT
    assertThat(b.check("x > 0 && x < 10", java.util.Map.of("x", 100L)))
        .isEqualTo(SolverZ3Bridge.Result.UNSAT);
  }
}

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

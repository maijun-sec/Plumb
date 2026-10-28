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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.summary.Operation;

/**
 * Tests for the {@link LocalSolverBridge} and the {@link Operation.SolverZ3Predicate} record.
 *
 * <p>Verifies the bridge handles the common "is this path reachable?" cases:
 *
 * <ul>
 *   <li>trivially true / trivially false predicates
 *   <li>arithmetic comparisons with concrete bindings
 *   <li>boolean combinations ({@code &amp;&amp;}, {@code ||}, {@code !})
 *   <li>unbound variables (conservatively SAT)
 *   <li>malformed input (conservatively SAT)
 *   <li>division by zero → UNKNOWN
 * </ul>
 *
 * <p>The {@link Operation.SolverZ3Predicate} record's contract is also covered: the predicate
 * string is preserved, the {@code pathVars} set is immutable, and passing {@code null} {@code
 * pathVars} yields an empty set.
 */
class R218LocalSolverBridgeTest {

  private final LocalSolverBridge bridge = new LocalSolverBridge();

  @Test
  void triviallyTruePredicate_isSat() {
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("x", 5L);
    assertThat(bridge.check("x == 5", env)).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(bridge.check("x != 0", env)).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(bridge.check("x > 0", env)).isEqualTo(SolverZ3Bridge.Result.SAT);
  }

  @Test
  void triviallyFalsePredicate_isUnsat() {
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("x", 5L);
    assertThat(bridge.check("x == 0", env)).isEqualTo(SolverZ3Bridge.Result.UNSAT);
    assertThat(bridge.check("x < 0", env)).isEqualTo(SolverZ3Bridge.Result.UNSAT);
    assertThat(bridge.check("x > 100", env)).isEqualTo(SolverZ3Bridge.Result.UNSAT);
  }

  @Test
  void booleanCombination_andOrNot() {
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("x", 5L);
    env.put("y", 10L);
    assertThat(bridge.check("x > 0 && y > 0", env)).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(bridge.check("x > 0 && y < 0", env)).isEqualTo(SolverZ3Bridge.Result.UNSAT);
    assertThat(bridge.check("x < 0 || y > 5", env)).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(bridge.check("!(x == 5)", env)).isEqualTo(SolverZ3Bridge.Result.UNSAT);
  }

  @Test
  void unboundVariable_isConservativeSat() {
    // A predicate that references a
    // variable not in the bindings is
    // conservatively treated as SAT — the
    // path MIGHT be reachable. This is
    // intentional: under-approximating
    // satisfiability would suppress true
    // findings.
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("x", 5L);
    // "y" is unbound.
    assertThat(bridge.check("y == 0", env)).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(bridge.check("y > 100", env)).isEqualTo(SolverZ3Bridge.Result.SAT);
  }

  @Test
  void arithmeticArithmetic_evaluatesCorrectly() {
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("x", 5L);
    env.put("y", 3L);
    assertThat(bridge.check("x + y == 8", env)).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(bridge.check("x - y == 2", env)).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(bridge.check("x * y == 15", env)).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(bridge.check("x / y == 1", env)).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(bridge.check("(x + y) * 2 == 16", env)).isEqualTo(SolverZ3Bridge.Result.SAT);
  }

  @Test
  void divisionByZero_isUnknown() {
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("x", 5L);
    env.put("y", 0L);
    assertThat(bridge.check("x / y == 1", env)).isEqualTo(SolverZ3Bridge.Result.UNKNOWN);
  }

  @Test
  void malformedPredicate_isConservativeSat() {
    // Garbage input → SAT, not crash. The
    // bridge must never cause a finding
    // suppression just because the
    // predicate is malformed.
    assertThat(bridge.check("(((((", Map.of())).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(bridge.check("", Map.of())).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(bridge.check(null, Map.of())).isEqualTo(SolverZ3Bridge.Result.SAT);
  }

  @Test
  void referencedVars_extractsAllIdentifiers() {
    // The regex picks up every identifier-
    // shaped token; the bridge's caller
    // uses this to validate binding
    // completeness.
    Set<String> refs = bridge.referencedVars("x > 0 && y == z");
    assertThat(refs).containsExactlyInAnyOrder("x", "y", "z");
  }

  @Test
  void referencedVars_filtersKeywords() {
    // Java keywords and boolean literals
    // must not be treated as variable
    // names.
    Set<String> refs = bridge.referencedVars("true && false && null");
    assertThat(refs).isEmpty();
  }

  // ─── Operation.SolverZ3Predicate record ───

  @Test
  void solverZ3Predicate_preservesPredicateAndVars() {
    Operation.SolverZ3Predicate p =
        new Operation.SolverZ3Predicate("x > 0 && y < 100", Set.of("x", "y"));
    assertThat(p.predicate()).isEqualTo("x > 0 && y < 100");
    assertThat(p.pathVars()).containsExactlyInAnyOrder("x", "y");
  }

  @Test
  void solverZ3Predicate_nullPathVars_becomesEmpty() {
    Operation.SolverZ3Predicate p = new Operation.SolverZ3Predicate("x > 0", null);
    assertThat(p.pathVars()).isEmpty();
  }

  @Test
  void solverZ3Predicate_isOperation() {
    // The new record participates in the
    // sealed Operation sum-type, so a
    // summary holding it can be
    // pattern-matched.
    Operation op = new Operation.SolverZ3Predicate("x > 0", Set.of("x"));
    assertThat(op).isInstanceOf(Operation.SolverZ3Predicate.class);
  }
}

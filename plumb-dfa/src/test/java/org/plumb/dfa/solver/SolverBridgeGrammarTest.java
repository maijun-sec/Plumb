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

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two solver bridges have to read a predicate the way the checkers wrote it.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The checkers write their predicates with the comparison inside the brackets -- {@code "((x +
 * 1) > LIMIT || (x + 1) < -LIMIT)"} -- because that is how the overflow and the underflow guards
 * are built. Three readings of that text were wrong, and each of them made the solver answer a
 * question nobody asked.
 *
 * <ul>
 *   <li><b>A bracketed comparison was read as arithmetic.</b> The Z3 bridge parsed the bracketed
 *       part as an arithmetic expression only. A comparison inside brackets was therefore not a
 *       comparison, the rest of the term was dropped, and a predicate like {@code ((x > 0) && (y >
 *       0))} was read as {@code (x > 0)}. Every term after the first one was lost, so a predicate
 *       that was false could be answered "satisfiable" and a finding that should have been
 *       suppressed was reported; and a predicate that was true could be answered "unsatisfiable"
 *       and a finding was lost.
 *   <li><b>A signed literal was read as a name.</b> {@code -2147483648} was taken for the name of a
 *       free variable, so the lower bound of every type range was an unknown value and the
 *       predicate was satisfiable. Fixing only this one is not enough: the bounds become readable,
 *       but the terms are still dropped.
 *   <li><b>An unbound variable was folded into a number.</b> The local bridge substituted the
 *       identifier it was not given with a marker that says "cannot decide", and then folded that
 *       marker into the arithmetic as if it were a number: {@code data * 2} became {@code 0 * 2},
 *       and the predicate was refuted. A path that might be reachable was reported as unreachable.
 * </ul>
 *
 * <p>Each test below pins ONE of those three readings, with a predicate in which the other two
 * readings do not matter, so a test that passes says something about the defect it names.
 */
class SolverBridgeGrammarTest {

  /** A disjunction in brackets: both terms have to survive the reading. */
  private static final String DISJUNCTION_IN_BRACKETS = "((x > 0) || (y > 0))";

  /** A conjunction in brackets: both terms have to survive the reading. */
  private static final String CONJUNCTION_IN_BRACKETS = "((x > 0) && (y > 0))";

  /** A signed literal, with no brackets in it, so nothing else can be at fault. */
  private static final String SIGNED_LITERAL = "0 + -1 > 0";

  /**
   * The overflow predicate of the boundary checker: the result of {@code 1 + 32767} is 32768, which
   * is inside the int range, so no value of anything makes the predicate true.
   */
  private static final String CONSTANT_FALSE =
      "(((1 + 32767)) > 2147483647 || ((1 + 32767)) < -2147483648)";

  /** The same predicate over a variable: some value of it makes the predicate true. */
  private static final String OVER_A_VARIABLE =
      "(((data * 2)) > 2147483647 || ((data * 2)) < -2147483648)";

  @Test
  @DisplayName("a bracketed disjunction keeps both of its terms")
  void aBracketedDisjunctionKeepsBothOfItsTerms() {
    // x is negative and y is positive, so the disjunction holds.
    assertThat(new Z3Bridge().check(DISJUNCTION_IN_BRACKETS, Map.of("x", -1L, "y", 1L)))
        .as(
            "the second term is the one that makes the disjunction true; a bridge that reads the"
                + " brackets as arithmetic drops it, answers UNSAT, and loses a finding")
        .isEqualTo(SolverZ3Bridge.Result.SAT);
  }

  @Test
  @DisplayName("a bracketed conjunction keeps both of its terms")
  void aBracketedConjunctionKeepsBothOfItsTerms() {
    // x is positive and y is negative, so the conjunction does not hold.
    assertThat(new Z3Bridge().check(CONJUNCTION_IN_BRACKETS, Map.of("x", 1L, "y", -1L)))
        .as(
            "the second term is the one that makes the conjunction false; a bridge that reads the"
                + " brackets as arithmetic drops it, answers SAT, and reports a finding that cannot"
                + " happen")
        .isEqualTo(SolverZ3Bridge.Result.UNSAT);
  }

  @Test
  @DisplayName("a signed literal is a number, not a name")
  void aSignedLiteralIsANumberNotAName() {
    assertThat(new Z3Bridge().check(SIGNED_LITERAL, Map.of()))
        .as(
            "0 + -1 is -1, which is not greater than 0; read as a name the minus sign would be a"
                + " free variable and the predicate would be satisfiable")
        .isEqualTo(SolverZ3Bridge.Result.UNSAT);
  }

  @Test
  @DisplayName("a variable the solver was not given makes the predicate satisfiable")
  void aVariableTheSolverWasNotGivenMakesThePredicateSatisfiable() {
    assertThat(new LocalSolverBridge().check(OVER_A_VARIABLE, Map.of()))
        .as(
            "the bridge was not given a value for `data`, so it cannot decide; the answer has to"
                + " be SAT, because the path might be reachable. Reading the unknown as 0 would"
                + " refute a predicate that some value of `data` satisfies")
        .isEqualTo(SolverZ3Bridge.Result.SAT);
  }

  @Test
  @DisplayName("the overflow predicate of the boundary checker is refuted")
  void theOverflowPredicateOfTheBoundaryCheckerIsRefuted() {
    assertThat(new Z3Bridge().check(CONSTANT_FALSE, Map.of()))
        .as(
            "the predicate is false for every value, so the solver must refute it; a SAT here means"
                + " the predicate was not read the way the checker wrote it and every reachability"
                + " check that relied on the solver was a check that never refused anything")
        .isEqualTo(SolverZ3Bridge.Result.UNSAT);
  }

  @Test
  @DisplayName("the two bridges agree on a predicate they can both decide")
  void theTwoBridgesAgreeOnAPredicateTheyCanBothDecide() {
    assertThat(new LocalSolverBridge().check(CONSTANT_FALSE, Map.of()))
        .as("the local bridge reads this predicate correctly")
        .isEqualTo(SolverZ3Bridge.Result.UNSAT);
    assertThat(new Z3Bridge().check(CONSTANT_FALSE, Map.of()))
        .as("the Z3 bridge has to reach the same answer as the local bridge")
        .isEqualTo(SolverZ3Bridge.Result.UNSAT);
  }
}

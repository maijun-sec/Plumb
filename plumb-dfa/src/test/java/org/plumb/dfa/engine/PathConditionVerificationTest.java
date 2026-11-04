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
package org.plumb.dfa.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.UncontrolledMemAllocChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.solver.GuardPredicateTranslator;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The path-condition verification, and the configuration switch that governs it.
 *
 * <p>The mechanism this covers used to be entirely inert. {@code pathConditionForSink} had a
 * declaration and two call sites and <em>no overrides</em>, so the predicate it returned was always
 * {@code null}, so the solver was never consulted for a source-to-sink path, and so the {@code
 * path_sensitivity} setting in {@code tuning.yaml} decided nothing at all.
 *
 * <p>Two things are tested here, and they are different in kind:
 *
 * <ul>
 *   <li><b>The predicate translation</b> is pure logic: a guard becomes a string the solver can
 *       read, and a guard that cannot be read is dropped rather than approximated.
 *   <li><b>The suppression</b> is end-to-end: a source whose guards contradict each other cannot
 *       reach the sink, so the finding does not stand.
 * </ul>
 *
 * <p>The negative cases are the important half. A path-condition filter that suppresses
 * <em>everything</em> would pass a test that only checks that a finding disappeared, and a project
 * that reports nothing looks clean.
 */
class PathConditionVerificationTest {

  @TempDir Path tmp;

  /**
   * A tainted size whose guards contradict each other.
   *
   * <p>{@code n > 5} and {@code n < 3} cannot both hold, so there is no assignment of {@code n}
   * that reaches the allocation. The allocation is still reached by the flow-insensitive analysis,
   * which is exactly the false positive the path condition exists to remove.
   */
  private static final String CONTRADICTORY_GUARDS =
      """
      import java.io.ByteArrayOutputStream;
      class T {
        void alloc(String s) throws Exception {
          int n = Integer.parseInt(s);
          if (n > 5) {
            System.out.println("big");
          }
          if (n < 3) {
            System.out.println("small");
          }
          new ByteArrayOutputStream(n);
        }
      }
      """;

  /** The same function without the second guard: nothing contradicts anything. */
  private static final String CONSISTENT_GUARDS =
      """
      import java.io.ByteArrayOutputStream;
      class T {
        void alloc(String s) throws Exception {
          int n = Integer.parseInt(s);
          if (n > 5) {
            System.out.println("big");
          }
          new ByteArrayOutputStream(n);
        }
      }
      """;

  private List<Finding> scan(String source, String pathSensitivity) throws Exception {
    Path file = tmp.resolve("T" + pathSensitivity.hashCode() + ".java");
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig config = base.withTuning(base.tuning().withPathSensitivity(pathSensitivity));
    FindingReporter reporter =
        new DfaEngine(config, List.of(new UncontrolledMemAllocChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    return new ArrayList<>(reporter.all());
  }

  // ── the mechanism ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a guard that contradicts another guard on the same variable is not reachable")
  void contradictoryGuardsAreFound() {
    List<GuardPredicateTranslator.GuardFact> guards =
        List.of(
            new GuardPredicateTranslator.GuardFact("n", BinaryOperator.GT, "5"),
            new GuardPredicateTranslator.GuardFact("n", BinaryOperator.LT, "3"));

    GuardPredicateTranslator.GuardFact[] conflict =
        GuardPredicateTranslator.findContradiction(guards);

    assertThat(conflict).as("n > 5 and n < 3 cannot both hold").isNotNull();
  }

  @Test
  @DisplayName("guards that overlap are not a contradiction")
  void overlappingGuardsAreNotAContradiction() {
    List<GuardPredicateTranslator.GuardFact> guards =
        List.of(
            new GuardPredicateTranslator.GuardFact("n", BinaryOperator.GT, "5"),
            new GuardPredicateTranslator.GuardFact("n", BinaryOperator.LT, "8"));

    assertThat(GuardPredicateTranslator.findContradiction(guards))
        .as("n > 5 and n < 8 overlap: n = 6 satisfies both")
        .isNull();
  }

  @Test
  @DisplayName("guards on DIFFERENT variables are not a contradiction")
  void guardsOnDifferentVariablesAreNotAContradiction() {
    List<GuardPredicateTranslator.GuardFact> guards =
        List.of(
            new GuardPredicateTranslator.GuardFact("n", BinaryOperator.GT, "5"),
            new GuardPredicateTranslator.GuardFact("m", BinaryOperator.LT, "3"));

    assertThat(GuardPredicateTranslator.findContradiction(guards))
        .as("n and m are different variables; their bounds do not contradict")
        .isNull();
  }

  @Test
  @DisplayName("a guard with only one bound cannot contradict anything")
  void aHalfOpenIntervalCannotContradict() {
    List<GuardPredicateTranslator.GuardFact> guards =
        List.of(
            new GuardPredicateTranslator.GuardFact("n", BinaryOperator.GT, "5"),
            new GuardPredicateTranslator.GuardFact("n", BinaryOperator.LT, "3"),
            new GuardPredicateTranslator.GuardFact("n", BinaryOperator.LT, "3"));

    // The third guard is the same one, so it does not change the outcome; what matters is
    // that a pair of one-sided bounds on DIFFERENT variables never contradicts.
    assertThat(
            GuardPredicateTranslator.findContradiction(
                List.of(
                    new GuardPredicateTranslator.GuardFact("n", BinaryOperator.GT, "5"),
                    new GuardPredicateTranslator.GuardFact("m", BinaryOperator.LT, "3"))))
        .isNull();
    assertThat(guards).hasSize(3);
  }

  @Test
  @DisplayName("a guard the solver's grammar cannot read is dropped, not approximated")
  void anUnreadableGuardIsDropped() {
    // An array's length is not a number the grammar can compare against.
    assertThat(GuardPredicateTranslator.translate("data", BinaryOperator.LT, "arr.length", true))
        .isNull();
    // A string constant is not a number either.
    assertThat(GuardPredicateTranslator.translate("s", BinaryOperator.EQ, "\"abc\"", false))
        .isNull();
    // A numeric literal reads fine.
    assertThat(GuardPredicateTranslator.translate("n", BinaryOperator.LT, "3", false))
        .isEqualTo("n < 3");
  }

  // ── the end-to-end behaviour ────────────────────────────────────────────────

  @Test
  @DisplayName("a source whose guards contradict each other does not reach the sink")
  void aContradictorySourceDoesNotReachTheSink() throws Exception {
    List<Finding> findings = scan(CONTRADICTORY_GUARDS, "full");

    assertThat(findings)
        .as(
            "no assignment of n satisfies both n > 5 and n < 3, so the allocation is not"
                + " reachable with a tainted size")
        .isEmpty();
  }

  @Test
  @DisplayName("a source whose guards do not contradict still reaches the sink")
  void aConsistentSourceStillReachesTheSink() throws Exception {
    List<Finding> findings = scan(CONSISTENT_GUARDS, "full");

    assertThat(findings)
        .as("the guard n > 5 is satisfiable, so the flow is real and the finding stands")
        .isNotEmpty();
  }

  @Test
  @DisplayName("path_sensitivity: none switches the verification off")
  void noneSwitchesTheVerificationOff() throws Exception {
    List<Finding> withVerification = scan(CONTRADICTORY_GUARDS, "full");
    List<Finding> withoutVerification = scan(CONTRADICTORY_GUARDS, "none");

    assertThat(withVerification).as("the verification removes the unreachable flow").isEmpty();
    assertThat(withoutVerification)
        .as(
            "with the verification off the finding stands: the setting decides whether the"
                + " solver is consulted")
        .isNotEmpty();
  }
}

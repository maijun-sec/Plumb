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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.UncontrolledMemAllocChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The solver's answer to the question a path condition actually asks.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>The engine asked the solver "is this path reachable?" and got an answer that could not depend
 * on the solver at all:
 *
 * <ul>
 *   <li>The condition it handed over was <b>the contradictory pair</b> of the source variable's
 *       guards — and the engine had already decided that pair arithmetically, one line earlier. So
 *       the solver was handed the very question that had been answered, and its answer could only
 *       ever agree. The solver's contribution to a taint rule was zero.
 *   <li>The question it was asked was the wrong one. A path condition asks whether SOME assignment
 *       of the values satisfies it, because the identifiers in it stand for values the program has
 *       not pinned down. The bridge method it used <em>evaluates</em> a predicate against given
 *       bindings, so an identifier it was not given was a value it could not produce: the pure-Java
 *       bridge answered "satisfiable" for every formula, and only the native Z3 could say
 *       otherwise. The engine's behaviour therefore depended on whether the machine had the native
 *       library — the same code, two answers.
 * </ul>
 *
 * <h2>What changed</h2>
 *
 * <p>The condition handed over is now the WHOLE conjunction of the guards in force at the sink, and
 * the question is now asked as a satisfiability question, through a method of its own. The
 * pure-Java bridge answers that question with {@link FreeConstantSolver}, which decides the same
 * shapes exactly as the native one does — so the two bridges agree, and the engine's behaviour does
 * not depend on the machine.
 *
 * <h2>Why the case below is the right one</h2>
 *
 * <p>The guards are {@code n >= 5}, {@code n <= 5} and {@code n != 5}. No PAIR of them conflicts:
 * the first two are satisfied together (by n = 5), and the third excludes nothing on its own. Only
 * the conjunction is unsatisfiable. So the arithmetic check — which compares guards two at a time —
 * cannot see it, and only a solver that reads the conjunction as a whole can. That is what makes
 * this case a witness for the solver rather than for the arithmetic.
 */
@DisplayName("Taint: the solver answers the path condition as a satisfiability question")
class SolverPathSatisfiabilityTest {

  private static final String RULE = "dfa-taint-uncontrolled-mem-alloc";

  @TempDir Path tmp;

  /**
   * A tainted number whose value the guards pin down impossibly.
   *
   * <p>Inside the two bounds the value can only be 5, and the third guard rules 5 out, so no value
   * reaches the allocation.
   */
  private static final String IMPOSSIBLE_VALUE =
      """
      class P1 {
        void go(java.io.BufferedReader reader) throws Exception {
          int n = Integer.parseInt(reader.readLine());
          if (n >= 5 && n <= 5) {
            if (n != 5) {
              StringBuilder sb = new StringBuilder(n);
              System.out.println(sb);
            }
          }
        }
      }
      """;

  /**
   * The control: the same two bounds, without the exclusion.
   *
   * <p>Here the value 5 satisfies both bounds, so the path is real and the finding stands.
   */
  private static final String POSSIBLE_VALUE =
      """
      class P2 {
        void go(java.io.BufferedReader reader) throws Exception {
          int n = Integer.parseInt(reader.readLine());
          if (n >= 5 && n <= 5) {
            StringBuilder sb = new StringBuilder(n);
            System.out.println(sb);
          }
        }
      }
      """;

  /**
   * A bridge that records the satisfiability questions and answers them with the real solver.
   *
   * <p>The recording is the point: a test that only asserts "no finding" cannot tell a suppressed
   * finding from a finding that was never produced.
   */
  private static final class SpyBridge implements SolverZ3Bridge {
    private final List<String> seen = new ArrayList<>();
    private final SolverZ3Bridge delegate = new Z3Bridge();

    /** When set, every satisfiability question is answered "satisfiable" instead. */
    private final boolean alwaysSatisfiable;

    SpyBridge() {
      this(false);
    }

    SpyBridge(boolean alwaysSatisfiable) {
      this.alwaysSatisfiable = alwaysSatisfiable;
    }

    @Override
    public Result check(String predicate, Map<String, Object> bindings) {
      return delegate.check(predicate, bindings);
    }

    @Override
    public Result checkSatisfiable(String predicate) {
      seen.add(predicate);
      if (alwaysSatisfiable) return Result.SAT;
      return delegate.checkSatisfiable(predicate);
    }

    @Override
    public Set<String> referencedVars(String predicate) {
      return delegate.referencedVars(predicate);
    }
  }

  private List<Finding> scan(String source, String name, SolverZ3Bridge bridge) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    UncontrolledMemAllocChecker checker = new UncontrolledMemAllocChecker();
    if (bridge != null) {
      checker.setSolverBridge(bridge);
    }
    FindingReporter reporter =
        new DfaEngine(base, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (RULE.equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  /**
   * The headline: a contradiction no single pair of guards can see suppresses the finding.
   *
   * <p>No pair of {@code n >= 5}, {@code n <= 5} and {@code n != 5} conflicts, so the arithmetic
   * check cannot see this. The conjunction has no solution, so the source cannot reach the
   * allocation, and the finding must not be reported.
   */
  @Test
  @DisplayName("a contradiction no single pair can see suppresses the finding")
  void aContradictionNoSinglePairCanSeeSuppressesTheFinding() throws Exception {
    SpyBridge spy = new SpyBridge();
    List<Finding> out = scan(IMPOSSIBLE_VALUE, "Impossible.java", spy);

    assertThat(out)
        .as(
            "no value satisfies all three guards, so the tainted number cannot reach the "
                + "allocation on this path")
        .isEmpty();
  }

  /**
   * The control: without the exclusion the value is possible, so the finding stands.
   *
   * <p>Without this, a test that asserts "no finding" would pass just as well on a program where
   * the taint never reached the sink in the first place — the assertion would be measuring the
   * fixture, not the mechanism.
   */
  @Test
  @DisplayName("without the exclusion the finding stands")
  void withoutTheExclusionTheFindingStands() throws Exception {
    SpyBridge spy = new SpyBridge();
    List<Finding> out = scan(POSSIBLE_VALUE, "Possible.java", spy);

    assertThat(out).as("the value 5 satisfies both bounds, so the path is real").isNotEmpty();
  }

  /**
   * The solver is what suppressed it.
   *
   * <p>A bridge that answers "satisfiable" for every question must let the finding through. If the
   * finding were still suppressed, the suppression would have come from somewhere else — the
   * arithmetic check — and this test would be measuring that instead of the solver.
   */
  @Test
  @DisplayName("a solver that says satisfiable does not suppress")
  void aSolverThatSaysSatisfiableDoesNotSuppress() throws Exception {
    SpyBridge spy = new SpyBridge(true);
    List<Finding> out = scan(IMPOSSIBLE_VALUE, "Impossible2.java", spy);

    assertThat(out)
        .as(
            "the arithmetic check compares guards two at a time and cannot see this "
                + "contradiction, so a solver that says 'satisfiable' must let the finding "
                + "through -- which is what makes the suppression above the solver's doing")
        .isNotEmpty();
  }

  /**
   * The solver was asked the WHOLE condition, not a contradictory pair of it.
   *
   * <p>Handing over the pair alone would hand the solver the question the arithmetic check had
   * already answered, and its answer could only ever agree.
   */
  @Test
  @DisplayName("the solver is asked the whole path condition")
  void theSolverIsAskedTheWholePathCondition() throws Exception {
    SpyBridge spy = new SpyBridge();
    scan(IMPOSSIBLE_VALUE, "Impossible3.java", spy);

    Set<String> predicates = new LinkedHashSet<>(spy.seen);
    assertThat(predicates).as("the solver was consulted at all").isNotEmpty();
    assertThat(predicates)
        .as("the condition carries every guard in force at the sink")
        .anyMatch(p -> p.contains("n >= 5") && p.contains("n <= 5") && p.contains("n != 5"));
  }

  /**
   * The two bridges answer the satisfiability question the same way.
   *
   * <p>This is the property that makes the engine's behaviour independent of the machine: the
   * native bridge asks Z3, the pure-Java bridge decides the same shapes itself, and a machine
   * without the native library gets the same answers.
   */
  @Test
  @DisplayName("the two bridges agree on the question they can both decide")
  void theTwoBridgesAgreeOnTheQuestionTheyCanBothDecide() {
    LocalSolverBridge local = new LocalSolverBridge();
    Z3Bridge native0 = new Z3Bridge();
    String[] decided = {
      "(x >= 5) && (x <= 5) && (x != 5)",
      "(x >= 5) && (x <= 5)",
      "(x > 0) && (x <= 0)",
      "(x > 5) && (x < 3)",
      "(x != 5) && (x != 6) && (x == 5)",
      "((x > 5) || (x < 3)) && ((x > 2) && (x < 4))",
    };
    for (String predicate : decided) {
      assertThat(local.checkSatisfiable(predicate))
          .as("the pure-Java bridge's answer to " + predicate)
          .isEqualTo(native0.checkSatisfiable(predicate));
    }
  }

  /**
   * The cache keeps the two questions apart.
   *
   * <p>This is a witness for the cache KEY, and for nothing else: no other case in this file goes
   * through the cache. The two questions can have different answers for the SAME predicate — the
   * evaluation question with no bindings answers "satisfiable" for anything with a free identifier,
   * while the satisfiability question may answer "unsatisfiable" — so they must not share a cache
   * entry. If they did, whichever answer arrived first would poison the other, and the engine would
   * silently fall back to the old machine-dependent behaviour.
   */
  @Test
  @DisplayName("the cache does not let one question's answer poison the other's")
  void theCacheDoesNotLetOneQuestionsAnswerPoisonTheOthers() {
    String predicate = "(x >= 5) && (x <= 5) && (x != 5)";
    // A bridge that answers the two questions DIFFERENTLY for the same predicate.
    CachedZ3Bridge cached =
        new CachedZ3Bridge(
            new SolverZ3Bridge() {
              @Override
              public Result check(String p, Map<String, Object> bindings) {
                return Result.SAT;
              }

              @Override
              public Result checkSatisfiable(String p) {
                return Result.UNSAT;
              }

              @Override
              public Set<String> referencedVars(String p) {
                return Set.of();
              }
            });

    assertThat(cached.check(predicate, Map.of()))
        .as("the evaluation question's answer")
        .isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(cached.checkSatisfiable(predicate))
        .as(
            "the satisfiability question's own answer -- the evaluation answer that was cached "
                + "first must not be served here")
        .isEqualTo(SolverZ3Bridge.Result.UNSAT);
  }

  /**
   * The evaluation question is unchanged.
   *
   * <p>The bridge's original contract — evaluate a predicate against the values it was given, and
   * answer "satisfiable" when it was not given one — still holds. The satisfiability question has a
   * method of its own, so it does not change what the old one means.
   */
  @Test
  @DisplayName("the evaluation contract is unchanged")
  void theEvaluationContractIsUnchanged() {
    assertThat(new LocalSolverBridge().check("(x >= 5) && (x <= 5) && (x != 5)", Map.of()))
        .as(
            "the bridge was not given a value for x, so it cannot evaluate the predicate; the "
                + "answer has to be SAT, because the path might be reachable")
        .isEqualTo(SolverZ3Bridge.Result.SAT);
  }

  /**
   * The EVALUATION question answers the same way with and without the native library.
   *
   * <p>This is a witness for the native bridge, and for nothing else in this file: no other case
   * here asks the evaluation question of the native bridge.
   *
   * <p>Why it matters: the satisfiability question can be answered by the native library AND by the
   * pure-Java one, so the engine's behaviour does not depend on the machine. The evaluation
   * question has no such property — the native library can "evaluate" a predicate whose identifier
   * it was never given by treating the identifier as a free integer constant, which is a DIFFERENT
   * question and can produce a different answer. The bridge therefore refuses to evaluate a
   * predicate that names an unbound identifier, and says "satisfiable", the same answer the
   * pure-Java bridge gives.
   *
   * <p>Without this, the native bridge would answer "unsatisfiable" for a predicate that is not
   * contradictory at all, and the engine's behaviour would depend on whether the machine had the
   * native library.
   */
  @Test
  @DisplayName("the evaluation question answers the same way with and without the native library")
  void theEvaluationQuestionAnswersTheSameWayWithAndWithoutTheNativeLibrary() {
    String predicate = "(x >= 5) && (x <= 5) && (x != 5)";

    assertThat(new Z3Bridge().check(predicate, Map.of()))
        .as(
            "the native bridge was not given a value for x either, so it cannot evaluate the "
                + "predicate; the answer has to be SAT, the same answer the pure-Java bridge gives")
        .isEqualTo(SolverZ3Bridge.Result.SAT);

    assertThat(new LocalSolverBridge().check(predicate, Map.of()))
        .as("the pure-Java bridge's answer to the same question")
        .isEqualTo(SolverZ3Bridge.Result.SAT);
  }
}

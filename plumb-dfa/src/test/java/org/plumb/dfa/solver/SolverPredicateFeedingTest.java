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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.TuningConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The cross-function path condition, measured.
 *
 * <p>The engine had the whole mechanism for a cross-function contradiction and it never fired. The
 * pieces were all there -- a solver bridge, a summary that carries path predicates, a composition
 * step -- but nothing connected the two sides, and the reason was structural rather than a missing
 * line:
 *
 * <ul>
 *   <li>The callee's predicates are about the CALLEE's variables, at the CALLEE's exit. The
 *       caller's guards are about the CALLER's source variable, at the CALLER's sink. Those are
 *       different variables at different points, so conjoining them is not a contradiction even
 *       when the solver says UNSAT -- it would be a coincidence of names.
 *   <li>The one cross-function fact that CAN contradict the caller's guards is a PRECONDITION: the
 *       callee says "this argument has to satisfy P, or the call throws". That is about the
 *       ARGUMENT, at the CALL -- the same variable the caller's own guards are about, at the same
 *       point.
 * </ul>
 *
 * <p>So the callee publishes the precondition, the caller instantiates it on the argument it
 * passes, and the solver is asked whether that argument can also satisfy the caller's guards. When
 * it cannot, the callee throws at this call, the call never returns, and nothing the callee returns
 * reaches the caller's sink.
 */
class SolverPredicateFeedingTest {

  private static final String RULE = "dfa-taint-sql-injection";

  @TempDir Path tmp;

  /**
   * A bridge that records every predicate it is asked about, and answers with the real solver.
   *
   * <p>The recording is the point: a test that only asserts "no finding" cannot tell a suppressed
   * finding from a finding that was never produced.
   *
   * <p>The delegate is the production default ({@link Z3Bridge}, which falls back to {@link
   * LocalSolverBridge} when the native library is missing). The fallback matters: it treats an
   * unbound variable as free and answers "satisfiable", so it can NEVER decide a contradiction over
   * a free variable. The suppression itself therefore does not depend on the solver -- the engine
   * decides that one arithmetically -- but the solver is still asked, and what it is asked is what
   * this test pins.
   */
  private static final class SpyBridge implements SolverZ3Bridge {
    private final List<String> seen = new ArrayList<>();
    private final SolverZ3Bridge delegate = new Z3Bridge();

    @Override
    public Result check(String predicate, Map<String, Object> bindings) {
      seen.add(predicate);
      return delegate.check(predicate, bindings);
    }

    @Override
    public Set<String> referencedVars(String predicate) {
      return delegate.referencedVars(predicate);
    }
  }

  private List<Finding> scan(String source, String name, SpyBridge spy) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            BuiltinFactKinds.registryWithBuiltins(),
            base.checkers(),
            base.cweMappings(),
            new TuningConfig(
                2,
                1000,
                1000,
                100,
                4,
                false,
                false,
                false,
                "full",
                "cwe_family",
                "sarif",
                false,
                0),
            base.receiverPassthroughPatterns());
    SqlInjectionChecker checker = new SqlInjectionChecker();
    if (spy != null) {
      checker.setSolverBridge(spy);
    }
    FindingReporter reporter =
        new DfaEngine(cfg, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (RULE.equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  /**
   * The callee publishes a precondition: the call throws unless the limit is positive.
   *
   * <p>The formal is deliberately named {@code lim} while the caller names its own variable {@code
   * limit}: the caller has to substitute the callee's name for its own, and a test where the two
   * names coincide would not notice if that substitution were missing.
   */
  private static final String CALLEE =
      """
      import java.sql.*;
      class T {
        static String check(String s, int lim) {
          if (lim <= 0) {
            throw new IllegalArgumentException("limit must be positive");
          }
          return s;
        }
      }
      """;

  /** The caller reaches the SQL sink through the callee, under a guard that contradicts it. */
  private static final String CALLER_GUARDED =
      """
      import java.sql.*;
      class T {
        static String check(String s, int lim) {
          if (lim <= 0) {
            throw new IllegalArgumentException("limit must be positive");
          }
          return s;
        }
        String bad(Connection conn, String userInput, int limit) throws SQLException {
          if (limit <= 0) {
            String v = check(userInput, limit);
            return conn.createStatement().executeQuery("SELECT * FROM t WHERE c = " + v).toString();
          }
          return "";
        }
      }
      """;

  /** The same shape with no guard on the argument: the call may complete, so the taint flows. */
  private static final String CALLER_UNGUARDED =
      """
      import java.sql.*;
      class T {
        static String check(String s, int lim) {
          if (lim <= 0) {
            throw new IllegalArgumentException("limit must be positive");
          }
          return s;
        }
        String bad(Connection conn, String userInput, int limit) throws SQLException {
          String v = check(userInput, limit);
          return conn.createStatement().executeQuery("SELECT * FROM t WHERE c = " + v).toString();
        }
      }
      """;

  /**
   * The headline: a cross-function contradiction suppresses the finding.
   *
   * <p>The caller's guard says {@code limit <= 0}; the callee requires {@code limit > 0}. The two
   * are about the same variable at the same point, so they contradict, the callee throws, the call
   * never returns, and the tainted value never reaches the SQL sink.
   */
  @Test
  void aCrossFunctionContradictionSuppressesTheFinding() throws Exception {
    SpyBridge spy = new SpyBridge();
    List<Finding> out = scan(CALLER_GUARDED, "Guarded.java", spy);

    assertThat(out)
        .as("the callee throws on this path, so nothing it returns reaches the sink")
        .isEmpty();
  }

  /**
   * The control: with no guard on the argument, the call may complete, the taint flows, and the
   * finding stands.
   *
   * <p>Without this, a test that asserts "no finding" would pass just as well on a program where
   * the taint never reached the sink in the first place -- the assertion would be measuring the
   * fixture, not the mechanism.
   */
  @Test
  void withoutTheContradictionTheFindingStands() throws Exception {
    SpyBridge spy = new SpyBridge();
    List<Finding> out = scan(CALLER_UNGUARDED, "Unguarded.java", spy);

    assertThat(out).as("the call may complete, so the tainted value reaches the sink").isNotEmpty();
  }

  /**
   * The solver was actually asked, and it was asked the right question.
   *
   * <p>The predicate has to mention the CALLER's variable name ({@code limit}), not the callee's
   * ({@code lim}): a conjunction about two different variables is not a contradiction, and the
   * bridge would answer "satisfiable" for it.
   */
  @Test
  void theSolverIsAskedAboutTheCallersOwnVariable() throws Exception {
    SpyBridge spy = new SpyBridge();
    scan(CALLER_GUARDED, "Guarded2.java", spy);

    Set<String> predicates = new LinkedHashSet<>(spy.seen);
    assertThat(predicates).as("the solver was consulted at all").isNotEmpty();
    assertThat(predicates)
        .as("the precondition was instantiated on the caller's variable")
        .anyMatch(p -> p.contains("limit > 0"));
    assertThat(predicates)
        .as("the conjunction carries the caller's own guard as well")
        .anyMatch(p -> p.contains("limit > 0") && p.contains("limit <= 0"));
    assertThat(predicates)
        .as("the callee's own variable name never appears -- it is the caller's variable now")
        .noneMatch(p -> p.contains("lim "));
  }
}

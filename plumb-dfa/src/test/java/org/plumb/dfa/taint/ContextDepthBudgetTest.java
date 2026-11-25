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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.summary.Operation;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The context depth is a BUDGET of call edges, not an on/off switch.
 *
 * <p><b>What went wrong.</b> {@code context_depth} was documented as "how deep the engine follows a
 * callee (0/1/2/3)", and every value above zero behaved the same way: the engine followed a taint
 * back through as many call edges as the program had. A user who set the depth to 1 and to 2
 * measured the same analysis twice and concluded the setting did nothing.
 *
 * <p><b>What this pins.</b> The depth is now the number of call edges the engine may cross between
 * the analysed function and the origin of the taint:
 *
 * <ul>
 *   <li>depth 0: no call edge at all, so every finding is inside one function.
 *   <li>depth 1: the origin may be in a direct callee.
 *   <li>depth 2: the origin may be in a callee of a callee.
 * </ul>
 *
 * <p>Each fixture below is a chain of calls with the source at the far end and the sink at the near
 * end, so the only question the depth answers is "how far back may the engine look".
 */
class ContextDepthBudgetTest {

  @TempDir Path tmp;

  /** The source is one call edge away from the sink. */
  private static final String ONE_EDGE =
      """
      import java.sql.Statement;
      class Source {
        String fetch() {
          return System.getenv("PLUMB_TEST_VALUE");
        }
      }
      class Caller {
        void start(Statement st) throws Exception {
          String value = new Source().fetch();
          st.executeQuery(value);
        }
      }
      """;

  /** The source is two call edges away from the sink. */
  private static final String TWO_EDGES =
      """
      import java.sql.Statement;
      class Source {
        String fetch() {
          return System.getenv("PLUMB_TEST_VALUE");
        }
      }
      class Mid {
        String middle() {
          return new Source().fetch();
        }
      }
      class Caller {
        void start(Statement st) throws Exception {
          String value = new Mid().middle();
          st.executeQuery(value);
        }
      }
      """;

  /** The source is three call edges away from the sink. */
  private static final String THREE_EDGES =
      """
      import java.sql.Statement;
      class Source {
        String fetch() {
          return System.getenv("PLUMB_TEST_VALUE");
        }
      }
      class Mid2 {
        String middle2() {
          return new Source().fetch();
        }
      }
      class Mid {
        String middle() {
          return new Mid2().middle2();
        }
      }
      class Caller {
        void start(Statement st) throws Exception {
          String value = new Mid().middle();
          st.executeQuery(value);
        }
      }
      """;

  /**
   * The taint enters at the TOP of the chain and travels DOWN it. The value the sink consumes is
   * the caller's own argument, carried through two calls and back.
   *
   * <p>This is the shape a budget must not punish: the taint never left the caller's frame, so the
   * call edges it travelled through cost it nothing.
   */
  private static final String PASS_THROUGH =
      """
      import java.sql.Statement;
      class Caller {
        void start(Statement st, String userInput) throws Exception {
          String value = helper2(helper1(userInput));
          st.executeQuery(value);
        }
        String helper1(String x) {
          return helper2(x);
        }
        String helper2(String x) {
          return x;
        }
      }
      """;

  private List<Finding> scan(String source, int contextDepth) throws Exception {
    Path file = tmp.resolve("Depth" + contextDepth + "_" + source.hashCode() + ".java");
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig config =
        base.withTuning(base.tuning().withPathSensitivity("full").withContextDepth(contextDepth));
    FindingReporter reporter =
        new DfaEngine(config, List.of(new SqlInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    return new ArrayList<>(reporter.all());
  }

  @Test
  @DisplayName("depth 0 keeps every finding inside one function")
  void depthZeroKeepsTheAnalysisInsideOneFunction() throws Exception {
    assertThat(scan(ONE_EDGE, 0))
        .as("the source is in a callee, and at depth 0 no call edge may be crossed")
        .isEmpty();
  }

  @Test
  @DisplayName("depth 1 reaches a source one call edge away")
  void depthOneReachesOneCallEdge() throws Exception {
    assertThat(scan(ONE_EDGE, 1)).as("one call edge is inside the budget of one").isNotEmpty();
  }

  @Test
  @DisplayName("depth 1 does not reach a source two call edges away")
  void depthOneDoesNotReachTwoCallEdges() throws Exception {
    assertThat(scan(TWO_EDGES, 1)).as("two call edges are outside the budget of one").isEmpty();
  }

  @Test
  @DisplayName("depth 2 reaches a source two call edges away, so depth 1 and depth 2 differ")
  void depthTwoReachesTwoCallEdges() throws Exception {
    assertThat(scan(TWO_EDGES, 2)).as("two call edges are inside the budget of two").isNotEmpty();
  }

  @Test
  @DisplayName("depth 2 does not reach a source three call edges away")
  void depthTwoDoesNotReachThreeCallEdges() throws Exception {
    assertThat(scan(THREE_EDGES, 2)).as("three call edges are outside the budget of two").isEmpty();
  }

  @Test
  @DisplayName("depth 3 reaches a source three call edges away")
  void depthThreeReachesThreeCallEdges() throws Exception {
    assertThat(scan(THREE_EDGES, 3))
        .as("three call edges are inside the budget of three")
        .isNotEmpty();
  }

  @Test
  @DisplayName("the caller's own taint is not charged for the calls it travelled through")
  void aPassThroughIsNotChargedForTheCallsItTravelledThrough() throws Exception {
    assertThat(scan(PASS_THROUGH, 1))
        .as("the taint is the caller's own argument, so the two call edges cost it nothing")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a summary records the depth of the proof it hands out")
  void aSummaryRecordsItsFoldDepth() {
    FunctionSummary.Builder b = new FunctionSummary.Builder("X.f/0");
    assertThat(b.build().foldDepth())
        .as("a summary that hands out no taint costs no depth")
        .isEqualTo(0);
    assertThat(b.build().hasFoldDepth()).as("and says so").isFalse();

    b.recordFoldDepth(2);
    assertThat(b.build().foldDepth()).as("the recorded depth is the summary's depth").isEqualTo(2);
    assertThat(b.build().hasFoldDepth()).as("and the summary knows it has one").isTrue();

    b.recordFoldDepth(1);
    assertThat(b.build().foldDepth())
        .as("the shallowest proof wins, because a summary is a may-fact")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("a merged summary takes the shallower of the two depths")
  void aMergedSummaryTakesTheShallowerDepth() {
    FunctionSummary.Builder deepBuilder = new FunctionSummary.Builder("X.f/0");
    deepBuilder.recordReturnOp(new Operation.TaintPropagate("return:x in X.f/0", 1.0));
    deepBuilder.recordFoldDepth(2);
    FunctionSummary deep = deepBuilder.build();

    FunctionSummary.Builder shallowBuilder = new FunctionSummary.Builder("X.f/0");
    shallowBuilder.recordReturnOp(new Operation.TaintPropagate("return:y in X.f/0", 1.0));
    shallowBuilder.recordFoldDepth(0);
    FunctionSummary shallow = shallowBuilder.build();

    assertThat(deep.merge(shallow).foldDepth())
        .as("the merged summary hands out the taint of both sides, so the cheaper proof applies")
        .isEqualTo(0);
    assertThat(shallow.merge(deep).foldDepth())
        .as("and the order of the merge does not change it")
        .isEqualTo(0);
  }

  @Test
  @DisplayName("a field write by another function is not consumed at depth 0")
  void aForeignFieldWriteIsNotConsumedAtDepthZero() {
    assertThat(TaintedCheckerBase.mayConsumeFieldTaintFrom(0, "X.write/0", "Y.read/0"))
        .as("depth 0 stays inside one function")
        .isFalse();
    assertThat(TaintedCheckerBase.mayConsumeFieldTaintFrom(0, "X.write/0", "X.write/0"))
        .as("a function may read what it wrote")
        .isTrue();
    assertThat(TaintedCheckerBase.mayConsumeFieldTaintFrom(0, null, "X.write/0"))
        .as("a write whose author is unknown may not be leaned on")
        .isFalse();
    assertThat(TaintedCheckerBase.mayConsumeFieldTaintFrom(1, "X.write/0", "Y.read/0"))
        .as("at depth 1 the program-wide field view is allowed")
        .isTrue();
  }
}

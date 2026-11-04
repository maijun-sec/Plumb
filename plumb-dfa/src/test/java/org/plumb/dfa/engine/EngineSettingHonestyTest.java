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
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The two engine settings that used to be read by nobody.
 *
 * <p>Both knobs were declared in {@code tuning.yaml} and in every checker's entry in {@code
 * checkers.yaml}, and neither was consulted by any analysis code. A user who set them and measured
 * the result would have measured the default, and concluded the setting worked.
 *
 * <p>The tests here pin what each knob now does:
 *
 * <ul>
 *   <li>{@code context_depth: 0} switches the cross-function summary folding off, so a flow that
 *       crosses a call boundary is no longer reported. That is the setting a reader of a report can
 *       check the findings against: every finding is inside one function.
 *   <li>{@code summary_cache_size} bounds the summary cache, and a refused summary is reported as
 *       an incomplete analysis rather than silently dropped.
 * </ul>
 */
class EngineSettingHonestyTest {

  @TempDir Path tmp;

  /**
   * A flow that crosses a call boundary.
   *
   * <p>The taint is introduced INSIDE {@code Source}, by a call {@code Caller} cannot see into. The
   * sink is in {@code Caller}, and the value that reaches it is a LOCAL, not a parameter.
   *
   * <p>Both halves of that matter:
   *
   * <ul>
   *   <li>The source is inside the callee, so the caller's own propagation cannot invent it.
   *   <li>The sink argument is the caller's local, so the finding depends on the callee's summary
   *       reaching the caller. A callee whose sink argument is its OWN parameter would report the
   *       finding either way, because every function parameter is a source in its own right -- that
   *       fixture would measure nothing.
   * </ul>
   */
  private static final String CROSS_FUNCTION =
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

  private List<Finding> scan(String source, int contextDepth, int summaryCacheSize)
      throws Exception {
    Path file = tmp.resolve("T" + contextDepth + "_" + summaryCacheSize + ".java");
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
  @DisplayName("context_depth 0 keeps the analysis inside one function")
  void contextDepthZeroStaysInsideOneFunction() throws Exception {
    List<Finding> findings = scan(CROSS_FUNCTION, 0, 1000);

    assertThat(findings)
        .as(
            "with no cross-function folding the value never reaches the callee's sink, so there"
                + " is no finding to report")
        .isEmpty();
  }

  @Test
  @DisplayName("context_depth 1 follows the call boundary")
  void contextDepthOneFollowsTheCallBoundary() throws Exception {
    List<Finding> findings = scan(CROSS_FUNCTION, 1, 1000);

    assertThat(findings).as("the callee's sink is reached across the call boundary").isNotEmpty();
  }

  @Test
  @DisplayName("the summary cache refuses a summary when it is full, and says so")
  void theSummaryCacheRefusesAndSaysSo() {
    EngineConfig base = ConfigLoader.loadFromClasspath();
    org.plumb.dfa.config.TuningConfig tuning = base.tuning().withSummaryCacheSize(1);
    org.plumb.dfa.summary.FunctionSummaryCache cache =
        new org.plumb.dfa.summary.FunctionSummaryCache(tuning);

    cache.put("a", org.plumb.dfa.summary.FunctionSummary.empty());
    cache.put("b", org.plumb.dfa.summary.FunctionSummary.empty());

    assertThat(cache.overflowCount())
        .as("the second summary did not fit, and the refusal is counted")
        .isEqualTo(1);
    assertThat(cache.size()).as("only one entry was kept").isEqualTo(1);
  }

  @Test
  @DisplayName("replacing an existing summary is not a refusal")
  void replacingAnExistingSummaryIsNotARefusal() {
    EngineConfig base = ConfigLoader.loadFromClasspath();
    org.plumb.dfa.config.TuningConfig tuning = base.tuning().withSummaryCacheSize(1);
    org.plumb.dfa.summary.FunctionSummaryCache cache =
        new org.plumb.dfa.summary.FunctionSummaryCache(tuning);

    cache.put("a", org.plumb.dfa.summary.FunctionSummary.empty());
    cache.put("a", org.plumb.dfa.summary.FunctionSummary.empty());

    assertThat(cache.overflowCount())
        .as("replacing an entry does not grow the cache, so nothing was refused")
        .isEqualTo(0);
  }

  @Test
  @DisplayName("a cache with no capacity limit never refuses")
  void anUnboundedCacheNeverRefuses() {
    EngineConfig base = ConfigLoader.loadFromClasspath();
    org.plumb.dfa.summary.FunctionSummaryCache cache =
        new org.plumb.dfa.summary.FunctionSummaryCache(base.tuning());

    for (int i = 0; i < 500; i++) {
      cache.put("s" + i, org.plumb.dfa.summary.FunctionSummary.empty());
    }

    assertThat(cache.overflowCount()).isEqualTo(0);
    assertThat(cache.size()).isEqualTo(500);
  }
}

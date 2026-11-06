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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;
import org.slf4j.LoggerFactory;

/**
 * The summary cache's hit/miss counters must be REPORTED, not merely kept.
 *
 * <p><b>What went wrong.</b> {@link org.plumb.dfa.summary.FunctionSummaryCache} has counted hits
 * and misses since it was written, and its own javadoc said "the post-run log reads these via
 * {@code hits()} / {@code misses()}". No call site existed. The counters were maintained, correct,
 * and invisible — which means a reader of the log could not tell whether the cross-function
 * analysis was earning its cost.
 *
 * <p><b>Why the hit rate matters.</b> It is the cheapest measure of whether the summary cache is
 * paying for itself. A cache that never hits means every callee is re-analysed; a cache that always
 * hits means the program has few distinct callees. Neither is an error, so the number belongs in
 * the log rather than in the budget's exhaustion record.
 *
 * <p><b>What this pins.</b> The log line exists and carries the numbers. A counter nobody reads is
 * a claim nobody can check.
 */
class SummaryCacheReportingTest {

  @TempDir Path tmp;

  /**
   * A source with a call boundary, so the cache is actually consulted.
   *
   * <p>The cache is only asked about a callee when there IS a callee, so a source with no calls
   * would produce a cache that is never read — and the test would measure nothing.
   */
  private static final String WITH_A_CALLEE =
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

  @Test
  @DisplayName("the run log reports the summary cache's hit/miss counts")
  void theRunLogReportsTheSummaryCacheCounts() throws Exception {
    List<String> lines = captureEngineLog(() -> scan(WITH_A_CALLEE));

    String cacheLine =
        lines.stream()
            .filter(l -> l.contains("Summary cache:"))
            .findFirst()
            .orElse(
                "the run log carried no summary-cache line; it carried: "
                    + String.join(" | ", lines));

    assertThat(cacheLine).as("the cache line must state the hit count").contains("hits");
    assertThat(cacheLine).as("the cache line must state the miss count").contains("misses");
    assertThat(cacheLine).as("the cache line must state the hit rate").contains("hit rate");
  }

  @Test
  @DisplayName("the reported hit rate is a real number, not a placeholder")
  void theReportedHitRateIsARealNumber() throws Exception {
    List<String> lines = captureEngineLog(() -> scan(WITH_A_CALLEE));

    String cacheLine =
        lines.stream()
            .filter(l -> l.contains("Summary cache:"))
            .findFirst()
            .orElse("the run log carried no summary-cache line");

    // The line reads "N hits, M misses (P% hit rate), E entries, capacity C".
    // A placeholder would be "0 hits, 0 misses (0% hit rate)" on every run, which
    // is indistinguishable from "the counters are not wired up".
    assertThat(cacheLine).containsPattern("\\d+ hits, \\d+ misses \\(\\d+% hit rate\\)");
  }

  @Test
  @DisplayName("a run that never consults the cache says so instead of dividing by zero")
  void aRunThatNeverConsultsTheCacheSaysSo() {
    // A cache with nothing in it has no hits and no misses. The ratio would be
    // 0/0, and a log line that printed "NaN%" would be a lie about a run that
    // simply did not use the cache.
    org.plumb.dfa.summary.FunctionSummaryCache cache =
        new org.plumb.dfa.summary.FunctionSummaryCache(ConfigLoader.loadFromClasspath().tuning());

    assertThat(cache.hits()).isZero();
    assertThat(cache.misses()).isZero();
  }

  /** Runs a scan and returns the engine's log lines. */
  private List<String> captureEngineLog(ThrowingAction action) throws Exception {
    Logger engineLogger = (Logger) LoggerFactory.getLogger(DfaEngine.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Level previous = engineLogger.getLevel();
    engineLogger.setLevel(Level.INFO);
    engineLogger.addAppender(appender);
    try {
      action.run();
    } finally {
      engineLogger.detachAppender(appender);
      engineLogger.setLevel(previous);
      appender.stop();
    }
    List<String> out = new ArrayList<>();
    for (ILoggingEvent event : appender.list) {
      out.add(event.getFormattedMessage());
    }
    return out;
  }

  /** Parses the source and runs the engine over it. */
  private void scan(String source) throws Exception {
    Path file = tmp.resolve("CacheReport.java");
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig config = ConfigLoader.loadFromClasspath();
    new DfaEngine(config, List.of(new SqlInjectionChecker()), "global", null)
        .runWithGlobalStore(List.of(program), 0);
  }

  /** An action that may throw a checked exception, so the scan helper can keep its own. */
  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }
}

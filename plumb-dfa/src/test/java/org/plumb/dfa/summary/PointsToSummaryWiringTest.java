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
package org.plumb.dfa.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The engine's own summary must carry the points-to operation — end to end, through the engine's
 * real code path rather than through the solver called by hand.
 *
 * <p><b>Why this test exists separately.</b> The other points-to tests call the solver directly,
 * which proves the pieces work. This one proves they are WIRED: that the engine's summary builder
 * reaches the solver, and that the operation lands in the summary the engine publishes to its
 * callers.
 *
 * <p>It reads the summary out of the engine's own cache rather than rebuilding it, because
 * rebuilding it would test the test's own copy of the logic instead of the engine's.
 */
class PointsToSummaryWiringTest {

  @TempDir Path tmp;

  /**
   * A method that allocates and returns an object.
   *
   * <p>The allocation is what gives the returned object an identity.
   */
  private static final String FACTORY =
      """
      class Box {
        String data;
      }
      class Factory {
        Box make() {
          return new Box();
        }
      }
      """;

  @Test
  @DisplayName("the engine's summary for a factory carries the points-to operation")
  void theEnginesSummaryCarriesThePointsToOperation() throws Exception {
    Path file = tmp.resolve("Factory.java");
    Files.writeString(file, FACTORY);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();

    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new SqlInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);

    assertThat(reporter.all()).as("the scan ran").isNotNull();

    // The engine's summary cache is reachable through the reporter's context,
    // which is the same object the engine published into.
    org.plumb.dfa.engine.AnalysisContext ctx = reporter.context();
    assertThat(ctx).as("the reporter carries the analysis context").isNotNull();

    FunctionSummaryCache cache = ctx.summaryCache();
    assertThat(cache).as("the engine published its summaries").isNotNull();

    // Find the factory's summary under any of the keys the engine stores it
    // under (the signature, the canonical callee key, the bare key).
    FunctionSummary found = null;
    for (String key : cache.snapshot().keySet()) {
      FunctionSummary s = cache.get(key);
      if (s == null) continue;
      boolean hasPointsTo = false;
      for (Operation op : s.returnOps()) {
        if (op instanceof Operation.PointsToPropagate) {
          hasPointsTo = true;
          break;
        }
      }
      if (hasPointsTo) {
        found = s;
        break;
      }
    }

    assertThat(found)
        .as(
            "the engine's summary must carry the return value's object identity -- otherwise"
                + " the PointsToPropagate operation has no producer and the caller's"
                + " destination never inherits the callee's allocation")
        .isNotNull();
  }
}

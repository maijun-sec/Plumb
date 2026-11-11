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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The cache of function summaries must have room for the program it is holding.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The cache was bounded at a fixed number of entries and REFUSED a summary when it was full,
 * rather than evicting one. The summaries that survived were therefore the ones that arrived first,
 * and "arrived first" is the order in which the parallel workers happened to finish. Which callee's
 * facts a caller could see depended on the schedule, and so did the findings.
 *
 * <p>The refusal is also a loss of facts: a refused summary never reaches its callers, so the
 * cross-function analysis is incomplete. The engine reports the refusals, but a report of an
 * incomplete analysis is not a fix for one.
 *
 * <h2>What it would catch</h2>
 *
 * <ul>
 *   <li>The capacity going back to a fixed number: a program with more functions than that number
 *       has its summaries refused, and the test fails.
 *   <li>The capacity ignoring the program's size: a bigger program gets the same cache as a smaller
 *       one, and the test fails.
 * </ul>
 */
class SummaryCacheCapacityTest {

  @TempDir Path tmp;

  /** A class with {@code count} methods, each with its own body. */
  private static String programWithMethods(int count) {
    StringBuilder sb = new StringBuilder("class Many {\n");
    for (int i = 0; i < count; i++) {
      sb.append("  int m").append(i).append("(int a) { return a + ").append(i).append("; }\n");
    }
    sb.append("}\n");
    return sb.toString();
  }

  private TacProgram translate(String source, String fileName) throws Exception {
    Path file = tmp.resolve(fileName);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    return new TacTranslator(ps).translate();
  }

  private AnalysisContext contextFor(TacProgram program) {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    return new AnalysisContext(program, null, config, new FindingReporter());
  }

  @Test
  @DisplayName("the cache grows with the program it holds")
  void theCacheGrowsWithTheProgram() throws Exception {
    int small =
        contextFor(translate(programWithMethods(20), "Small.java")).summaryCache().capacity();
    int large =
        contextFor(translate(programWithMethods(200), "Large.java")).summaryCache().capacity();

    assertThat(large)
        .as(
            "a bigger program needs more summaries, so the cache that holds them must grow with"
                + " it; a capacity that does not grow refuses the summaries of the functions that"
                + " do not fit, and the findings then depend on the order the workers finished in")
        .isGreaterThan(small);
  }

  @Test
  @DisplayName("no summary is refused for a program the cache has room for")
  void noSummaryIsRefusedForAProgramTheCacheHasRoomFor() throws Exception {
    TacProgram program = translate(programWithMethods(600), "Many.java");
    AnalysisContext ctx = contextFor(program);

    // The engine stores a function's summary under its signature and under a qualified alias, so
    // that a call site finds it whichever way it names the callee. Two keys per function is what
    // this test stores.
    int stored = 0;
    for (TacFunction fn : program.functions()) {
      ctx.summaryCache().put(fn.signature(), FunctionSummary.empty());
      ctx.summaryCache()
          .put(fn.enclosingClass() + " :: " + fn.signature(), FunctionSummary.empty());
      stored += 2;
    }

    assertThat(ctx.summaryCache().overflowCount())
        .as(
            "a refused summary never reaches its callers, so the cross-function analysis is"
                + " incomplete; the cache has to have room for the program's summaries")
        .isZero();
    assertThat(ctx.summaryCache().size())
        .as("every stored summary must be in the cache")
        .isEqualTo(stored);
  }
}

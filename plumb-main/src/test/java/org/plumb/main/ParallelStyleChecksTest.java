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
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.rules.RuleRegistry;

/**
 * The parallel parse phase has to report the same style findings as a one-file-at-a-time run.
 *
 * <p><b>What went wrong.</b> The workers shared one set of AST checkers, and the checkers hold the
 * state of the file they are looking at. Two workers running the same checker instance are two
 * files writing into one state, so a worker's init() cleared what the other one had collected and
 * the checker threw while the other worker was reading it. The file that lost its findings was
 * whichever one the scheduler picked.
 *
 * <p>The consequence for a reader of the report is worse than a missing finding: the report is not
 * merely smaller, it is DIFFERENT from one run to the next, and the difference lands on the rules
 * that keep per-file state. On a 7047-file corpus the shared instances made 2407 rule visits fail
 * across 1940 files, and the duplicated-literal rule alone moved by 1510 findings between two runs
 * of the SAME build with the SAME configuration.
 *
 * <p><b>What this pins.</b> The parallel phase and the one-at-a-time phase have to agree, file by
 * file. The one-at-a-time run is the oracle: with a single file there is no second worker to
 * interfere, so its counts are the ones the rules would report if they were not stepping on each
 * other.
 */
class ParallelStyleChecksTest {

  @Test
  @DisplayName("the parallel phase reports the same style findings as the one-at-a-time phase")
  void theParallelPhaseAgreesWithTheOneAtATimePhase(@TempDir Path dir) throws Exception {
    List<Path> sources = writeFiles(dir, 240);
    ScanConfig cfg = new ScanConfig();
    cfg.runStyle = true;
    ScanRunner runner = new ScanRunner(cfg);
    // The parallelism plan is decided in run(), which this test never reaches, so the parse
    // phase would otherwise run with ONE worker -- and one worker never overlaps another one,
    // so it cannot show anything about the state the workers share.
    int workers = runner.useParseWorkers(4);
    Assumptions.assumeTrue(
        workers >= 2, "the machine has to be able to run two parse workers at once");

    Map<String, Integer> parallel =
        styleCounts(runner.translateAll(sources, new RuleRegistry(), () -> true));

    Map<String, Integer> oneAtATime = new HashMap<>();
    for (Path source : sources) {
      for (ScanRunner.TranslatedFile file :
          runner.translateAll(List.of(source), new RuleRegistry(), () -> true)) {
        oneAtATime.merge(file.file().toString(), file.styleFindings().size(), Integer::sum);
      }
    }

    assertThat(parallel)
        .as(
            "a worker must not be able to clear another worker's file state; the files that lost"
                + " their findings were the ones the scheduler happened to pick")
        .isEqualTo(oneAtATime);
    assertThat(parallel)
        .as("the oracle run has to have found something, or the comparison proves nothing")
        .isNotEmpty();
  }

  /** The style findings of a phase-1 run, by file. */
  private static Map<String, Integer> styleCounts(List<ScanRunner.TranslatedFile> translated) {
    Map<String, Integer> out = new HashMap<>();
    for (ScanRunner.TranslatedFile file : translated) {
      out.merge(file.file().toString(), file.styleFindings().size(), Integer::sum);
    }
    return out;
  }

  /**
   * Write {@code count} Java files that each hold the same string literal three times.
   *
   * <p>Three occurrences of one literal are what the duplicated-literal rule needs to fire, and the
   * rule keeps the positions of the literal in the checker instance -- which is exactly the state
   * two workers used to share.
   */
  private static List<Path> writeFiles(Path dir, int count) throws Exception {
    List<Path> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Path f = dir.resolve("Sample" + i + ".java");
      Files.writeString(
          f,
          "package demo;\n"
              + "class Sample"
              + i
              + " {\n"
              + "  String m() {\n"
              + "    String a = \"a-duplicated-literal\";\n"
              + "    String b = \"a-duplicated-literal\";\n"
              + "    String c = \"a-duplicated-literal\";\n"
              + "    return a + b + c;\n"
              + "  }\n"
              + "}\n");
      out.add(f);
    }
    return out;
  }
}

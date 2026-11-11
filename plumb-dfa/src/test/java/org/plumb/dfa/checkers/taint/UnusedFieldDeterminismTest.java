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
package org.plumb.dfa.checkers.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The unused-private-field rule must answer the same on every run.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The rule asks a question about the WHOLE program -- "is this field ever read?" -- but it
 * answered it while one function was being analysed, with whatever the analysis had seen so far. A
 * field read by a function visited later therefore looked unread, and the finding landed on
 * whichever function happened to be visited first.
 *
 * <p>With more than one worker the answer depends on the schedule: each worker has its own copy of
 * the checker, so each of them can report the same field, and a field that is read looks unread or
 * read depending on which worker got there first.
 *
 * <h2>What it would catch</h2>
 *
 * <ul>
 *   <li>The emission moving back into the per-function path: the worker counts would differ, and
 *       the test fails.
 *   <li>The "is it ever read?" question being answered before the program is complete: the field
 *       that IS read is reported, and the test fails.
 * </ul>
 */
class UnusedFieldDeterminismTest {

  @TempDir Path tmp;

  /**
   * A field that IS read -- by a function that the analysis visits after the one that writes it.
   *
   * <p>The rule must not report it: the whole program contains the read.
   */
  private static final String READ_LATER =
      """
      class Unused {
        private String slot;

        void write(String v) {
          slot = v;
        }

        void read() {
          System.out.println(slot);
        }
      }
      """;

  /** A field that nothing reads. The rule must report it, exactly once. */
  private static final String NEVER_READ =
      """
      class Unused {
        private String slot;

        void write(String v) {
          slot = v;
        }
      }
      """;

  private List<String> run(String source, String fileName, int threads) throws Exception {
    Path file = tmp.resolve(fileName);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig config = ConfigLoader.loadFromClasspath();
    List<DfaChecker> checkers = List.of(new UnusedFieldChecker());
    // Each worker gets its OWN checkers: a checker carries per-function state, so a shared
    // instance would have one worker clearing another worker's state mid-walk.
    java.util.function.Supplier<List<DfaChecker>> factory = () -> List.of(new UnusedFieldChecker());
    FindingReporter reporter =
        new DfaEngine(config, checkers, "global", null, threads, factory)
            .runWithGlobalStore(List.of(program), threads);
    List<String> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (!"dfa-taint-unused-private-field".equals(f.ruleId())) continue;
      out.add(f.ruleId() + "@" + f.file() + ":" + f.line() + " | " + f.message());
    }
    out.sort(String::compareTo);
    return out;
  }

  @Test
  @DisplayName("a field the program reads is not reported as unused")
  void aFieldTheProgramReadsIsNotReportedUnused() throws Exception {
    List<String> findings = run(READ_LATER, "Unused.java", 0);

    assertThat(findings)
        .as(
            "the program reads the field in another function, so the field is NOT unused; a"
                + " report here means the rule answered the question before it had seen the"
                + " whole program")
        .isEmpty();
  }

  @Test
  @DisplayName("the findings are the same at every worker count")
  void theFindingsAreTheSameAtEveryWorkerCount() throws Exception {
    List<String> sequential = run(READ_LATER, "Unused.java", 0);

    for (int threads : new int[] {1, 2, 4}) {
      assertThat(run(READ_LATER, "Unused.java", threads))
          .as(
              "the findings must not depend on how many workers ran: the rule asks a question"
                  + " about the whole program, and the answer must not be a schedule")
          .isEqualTo(sequential);
    }
  }

  @Test
  @DisplayName("a field nothing reads is reported exactly once")
  void aFieldNothingReadsIsReportedExactlyOnce() throws Exception {
    for (int threads : new int[] {0, 1, 2, 4}) {
      List<String> findings = run(NEVER_READ, "Unused.java", threads);

      assertThat(findings)
          .as("the field is never read, so the rule must report it, at every worker count")
          .hasSize(1);
      assertThat(findings.get(0)).contains("'slot'");
    }
  }
}

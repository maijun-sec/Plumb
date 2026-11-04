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
import org.plumb.dfa.checkers.controlflow.UncaughtExceptionPropagator;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The parallel analysis must produce the SAME findings as the sequential one.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The parallel worklist was implemented but never used: every production call passed zero
 * workers. The reason was not written down anywhere, and it was not a design decision -- it was
 * that the parallel path was <b>wrong</b>.
 *
 * <p>The per-function state -- the fact bag and the side-effect consumer -- was ONE instance shared
 * by every worker. Each worker cleared it and wrote to it while the others were doing the same, so
 * one worker's {@code clear()} erased another worker's facts mid-analysis. The result would have
 * been findings that depend on the thread interleaving: a different set on every run, and a
 * different set from the sequential run.
 *
 * <p>So the state became per-thread, and this test is the proof that it works. It runs the SAME
 * program twice -- once with one worker, once with several -- and asserts the findings are
 * identical.
 *
 * <h2>What it would catch</h2>
 *
 * <ul>
 *   <li>A shared fact bag: the two runs would differ, and the test fails.
 *   <li>A checker that mutates shared state (the finding reporter, the summary cache): the same.
 *   <li>An ordering dependency: the same.
 * </ul>
 *
 * <p>The fixture is deliberately a MULTI-FUNCTION program with cross-function calls, because a
 * single function has nothing to race over.
 */
class ParallelAnalysisConsistencyTest {

  @TempDir Path tmp;

  /**
   * A program with several functions that call each other, so the analysis has cross-function
   * summaries to compute and the workers have shared state to race over.
   *
   * <p>Two of the sinks are reached THROUGH a helper, so the summary cache is exercised: the taint
   * has to survive a return value and a call boundary for the finding to appear at all.
   */
  private static final String PROGRAM =
      """
      import java.io.File;
      import java.io.FileOutputStream;
      import java.io.OutputStream;

      class Parallel {
        void entry(String userInput) throws Exception {
          String enriched = enrich(userInput);
          execDirect(enriched);
          execThroughTwoHops(enriched);
          writeFile(enriched);
        }

        private String enrich(String s) {
          return "prefix:" + s;
        }

        private void execDirect(String cmd) throws Exception {
          Runtime.getRuntime().exec(cmd);
        }

        private void execThroughTwoHops(String cmd) throws Exception {
          execDirect(tag(cmd));
        }

        private String tag(String s) {
          return "[" + s + "]";
        }

        private void writeFile(String data) throws Exception {
          OutputStream os = new FileOutputStream(new File("out.txt"));
          os.write(data.getBytes());
          os.close();
        }
      }
      """;

  private List<String> run(int threads) throws Exception {
    // The SAME file name every time: the finding carries the file it was found in, so a
    // per-thread file name would make the comparison fail for a reason that has nothing to do
    // with the parallelism.
    Path file = tmp.resolve("Parallel.java");
    Files.writeString(file, PROGRAM);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig config = ConfigLoader.loadFromClasspath();
    List<DfaChecker> checkers = List.of(new CommandInjectionChecker());
    // The factory gives each worker its OWN checkers. A checker carries per-function state, so a
    // shared instance would have one worker's initFunction clear another worker's facts.
    java.util.function.Supplier<List<DfaChecker>> factory =
        () -> List.of(new CommandInjectionChecker());
    FindingReporter reporter =
        new DfaEngine(config, checkers, "global", null, threads, factory)
            .runWithGlobalStore(List.of(program), threads);
    List<String> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      out.add(f.ruleId() + "@" + f.file() + ":" + f.line() + ":" + f.message());
    }
    out.sort(String::compareTo);
    return out;
  }

  @Test
  @DisplayName("the parallel analysis finds exactly what the sequential one finds")
  void parallelAndSequentialAgree() throws Exception {
    List<String> sequential = run(0);
    List<String> parallel = run(4);
    assertThat(sequential)
        .as(
            "the fixture must actually produce findings, or the comparison below is vacuous: two"
                + " empty lists are equal")
        .isNotEmpty();
    assertThat(parallel)
        .as(
            "the parallel worklist must produce the same findings as the sequential one; a"
                + " difference means the per-function state is shared between workers")
        .isEqualTo(sequential);
  }

  @Test
  @DisplayName("the parallel analysis is repeatable")
  void parallelIsRepeatable() throws Exception {
    List<String> first = run(4);
    List<String> second = run(4);
    assertThat(first).as("the fixture must produce findings").isNotEmpty();
    assertThat(second).as("two parallel runs over the same program must agree").isEqualTo(first);
  }

  /**
   * A caller that calls a throwing callee, with no try-catch.
   *
   * <p>The cross-function CWE-248 finding needs TWO facts: the callee throws, and the caller has no
   * catch. The callee's fact lives in a DIFFERENT function than the caller's, so this is the case
   * that the whole-program fact store exists for.
   */
  private static final String THROWING_PROGRAM =
      """
      class Throwing {
        void boom() {
          throw new RuntimeException("boom");
        }
        void caller() {
          boom();
        }
      }
      """;

  private List<String> runThrower(int threads) throws Exception {
    Path file = tmp.resolve("Throwing.java");
    Files.writeString(file, THROWING_PROGRAM);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig config = ConfigLoader.loadFromClasspath();
    List<DfaChecker> checkers = List.of(new UncaughtExceptionPropagator());
    java.util.function.Supplier<List<DfaChecker>> factory =
        () -> List.of(new UncaughtExceptionPropagator());
    FindingReporter reporter =
        new DfaEngine(config, checkers, "global", null, threads, factory)
            .runWithGlobalStore(List.of(program), threads);
    List<String> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (!"dfa-controlflow-uncaught-throw".equals(f.ruleId())) continue;
      out.add(f.ruleId() + "@" + f.file() + ":" + f.line());
    }
    out.sort(String::compareTo);
    return out;
  }

  @Test
  @DisplayName("the cross-function finding survives the parallel worklist")
  void crossFunctionFindingSurvivesParallelism() throws Exception {
    List<String> sequential = runThrower(0);
    assertThat(sequential)
        .as(
            "the fixture must produce the cross-function finding sequentially, or the comparison"
                + " below is vacuous")
        .isNotEmpty();
    for (int threads : new int[] {1, 2, 4}) {
      assertThat(runThrower(threads))
          .as(
              "the cross-function finding must be the same at every worker count: it needs a fact"
                  + " about the CALLEE (that it throws) and a fact about the CALLER (that it has no"
                  + " catch), and the two live in different functions -- so the facts must be"
                  + " visible to every worker, not held by one checker's instance")
          .isEqualTo(sequential);
    }
  }
}

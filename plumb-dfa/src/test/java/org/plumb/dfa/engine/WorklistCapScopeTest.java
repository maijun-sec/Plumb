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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.TuningConfig;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The worklist cap must bound ONE function's worklist, not the whole run's.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The cap is named after the size of the worklist it guards. That worklist is the fact bag, and
 * the fact bag is cleared at the start of every function -- so the cap has to be counted per
 * function.
 *
 * <p>It was not. The counter behind the cap was created once per run and never reset, so the cap
 * was a budget for the WHOLE run wearing the name of a per-function worklist size. After the first
 * N facts the engine refused every fact it was offered, and how much interprocedural analysis
 * survived depended on the size of the corpus rather than on the code being analysed. A scan of a
 * large corpus lost almost all of it; a scan of a small one lost none. Nothing in the report said
 * so beyond a count of refusals.
 *
 * <h2>What it would catch</h2>
 *
 * <ul>
 *   <li>A cap that is counted per run: the second function's fact is refused, the analysis is
 *       reported as incomplete, and the first test fails.
 *   <li>A cap that has been switched off: the second test fails -- the two mistakes cannot cancel
 *       each other out.
 * </ul>
 *
 * <p>The fixture calls a method that writes a field, once per function. A callee that writes is not
 * pure, so the engine records a side-effect fact at the call site -- which is the fact the cap
 * counts. Every function records exactly one, so a cap of one is within it.
 *
 * <p>The call goes through a typed receiver on purpose. The IR names a call site by the name of the
 * receiver's type, so a call on a typed receiver is {@code Caps.write/1} and the engine can find
 * the callee's summary. A call on {@code this} is just {@code write/1}, and the engine cannot
 * resolve that to a summary at all -- so a fixture written that way would record nothing, and the
 * assertions below would be vacuous. The test says so when it happens: the guard on the fact count
 * fails instead of the test passing for the wrong reason.
 *
 * <p>These tests use only the API that existed before the fix, so they can be run against the old
 * behaviour: a test that cannot compile against the broken code cannot demonstrate that the broken
 * code was broken.
 */
class WorklistCapScopeTest {

  @TempDir Path tmp;

  /** Every function records exactly one fact. */
  private static final String PROGRAM =
      """
      class Caps {
        private String slot;

        void write(String v) {
          slot = v;
        }

        void one(Caps other, String v) {
          other.write(v);
        }

        void two(Caps other, String v) {
          other.write(v);
        }

        void three(Caps other, String v) {
          other.write(v);
        }
      }
      """;

  /** One function records three facts, so it passes a cap of one on its own. */
  private static final String HEAVY_PROGRAM =
      """
      class Heavy {
        private String slot;

        void write(String v) {
          slot = v;
        }

        void heavy(Heavy other, String v) {
          other.write(v);
          other.write(v);
          other.write(v);
        }
      }
      """;

  private DfaEngine run(EngineConfig config, String source, String fileName) throws Exception {
    Path file = tmp.resolve(fileName);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    List<DfaChecker> checkers = List.of(new CommandInjectionChecker());
    DfaEngine engine =
        new DfaEngine(
            config, checkers, "global", null, 0, () -> List.of(new CommandInjectionChecker()));
    engine.runWithGlobalStore(List.of(program), 0);
    return engine;
  }

  /** The same configuration, with the worklist cap replaced. */
  private EngineConfig withCap(EngineConfig base, int cap) {
    TuningConfig t = base.tuning();
    return base.withTuning(
        new TuningConfig(
            t.contextDepth(),
            cap,
            t.z3TimeoutMs(),
            t.summaryCacheSize(),
            t.reverseWalkMaxDepth(),
            t.preScanEnabled(),
            t.libraryCallClassification(),
            t.taintCallerStringConcat(),
            t.pathSensitivity(),
            t.reportingGroupBy(),
            t.outputFormat(),
            t.parallel(),
            t.perFunctionTimeoutMs()));
  }

  @Test
  @DisplayName("a cap of one still lets every function record its fact")
  void theCapIsCountedPerFunction() throws Exception {
    EngineConfig base = ConfigLoader.loadFromClasspath();
    DfaEngine engine = run(withCap(base, 1), PROGRAM, "Caps.java");

    assertThat(engine.budget().factsTracked())
        .as(
            "the fixture must record at least one fact per function, or the assertion below says"
                + " nothing")
        .isGreaterThanOrEqualTo(2);

    assertThat(engine.budget().report().complete())
        .as(
            "the cap is the size of ONE function's worklist: each function records one fact, so"
                + " none of them passes the cap")
        .isTrue();
  }

  @Test
  @DisplayName("a function that records more than the cap is still refused")
  void theCapStillBitesWithinAFunction() throws Exception {
    EngineConfig base = ConfigLoader.loadFromClasspath();
    DfaEngine engine = run(withCap(base, 1), HEAVY_PROGRAM, "Heavy.java");

    assertThat(engine.budget().factsTracked())
        .as("the fixture must record more than one fact, or the assertion below says nothing")
        .isGreaterThanOrEqualTo(3);

    assertThat(engine.budget().report().complete())
        .as(
            "the cap is not switched off: a function that records three facts with a cap of one is"
                + " refused")
        .isFalse();
  }
}

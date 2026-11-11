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
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * A call to a method of the caller's own class has to find THAT method's summary.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The IR names a same-class call by the method alone -- {@code write/1} -- with no class in it.
 * The summary cache was keyed by the method name alone too, but the key builder REFUSED a key
 * without a class in it, so those calls never found anything: the callee's return shape was never
 * folded into the caller, and the callee's side effects were never recorded.
 *
 * <p>So the cross-function analysis was silently absent for the most common call there is, and
 * nothing in the report said so.
 *
 * <h2>What it would catch</h2>
 *
 * <ul>
 *   <li>A call site that asks under a class the engine does not store under: the fact is not
 *       recorded, and this test fails.
 *   <li>A key builder that goes back to refusing a key without a class: the same.
 * </ul>
 *
 * <p>The fixture writes a field, so the callee is not pure and the engine records a fact for it. A
 * guard on the fact count fails loudly if the fixture stops producing facts, so the test cannot
 * pass for the wrong reason.
 */
class UnqualifiedCallFoldingTest {

  @TempDir Path tmp;

  private static final String PROGRAM =
      """
      class Caps {
        private String slot;

        void write(String v) {
          slot = v;
        }

        void caller(String v) {
          write(v);
        }
      }
      """;

  private DfaEngine run(String source, String fileName) throws Exception {
    Path file = tmp.resolve(fileName);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig config = ConfigLoader.loadFromClasspath();
    DfaEngine engine =
        new DfaEngine(
            config,
            List.of(new CommandInjectionChecker()),
            "global",
            null,
            0,
            () -> List.of(new CommandInjectionChecker()));
    engine.runWithGlobalStore(List.of(program), 0);
    return engine;
  }

  @Test
  @DisplayName("a call to a method of the caller's own class finds that method's summary")
  void anUnqualifiedCallFindsTheCalleesSummary() throws Exception {
    DfaEngine engine = run(PROGRAM, "Caps.java");

    assertThat(engine.budget().factsTracked())
        .as(
            "the callee writes a field, so it is not pure and the engine records it; a count of"
                + " zero means the call site found nothing")
        .isGreaterThanOrEqualTo(1);
  }
}

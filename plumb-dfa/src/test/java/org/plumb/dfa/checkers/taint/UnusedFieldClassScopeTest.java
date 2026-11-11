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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Two classes that declare a field of the SAME name are two fields.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The rule keys a field by its name alone, so two classes that both declare a field called
 * {@code slot} share one key. The rule can then report at most one of them, and one class's read
 * suppresses the other class's report.
 *
 * <p>The key is now the field's CLASS as well as its name, and the class is the type of the
 * receiver the access is made on -- the thing the field hangs on. That is in the IR already: the
 * receiver of a field access is an operand, and an operand carries the type of the thing it names.
 *
 * <h2>What it would catch</h2>
 *
 * <ul>
 *   <li>the key falling back to the name alone: the two classes share a key again, only one report
 *       comes out, and the test fails;
 *   <li>the key using the class the access is WRITTEN in instead of the class the field belongs to:
 *       a field read from another class stops matching its own write, the field looks unread, and
 *       the test fails.
 * </ul>
 */
class UnusedFieldClassScopeTest {

  @TempDir Path tmp;

  /** A class whose field is never read. */
  private static final String NEVER_READ_A =
      """
      class ScopeA {
        private String slot;

        void write(String v) {
          slot = v;
        }
      }
      """;

  /** Another class, with a field of the same name, also never read. */
  private static final String NEVER_READ_B =
      """
      class ScopeB {
        private String slot;

        void write(String v) {
          slot = v;
        }
      }
      """;

  /** A class whose field IS read. */
  private static final String IS_READ_C =
      """
      class ScopeC {
        private String slot;

        void write(String v) {
          slot = v;
        }

        void read() {
          System.out.println(slot);
        }
      }
      """;

  /** A class with a field of the same name that nothing reads. */
  private static final String NEVER_READ_D =
      """
      class ScopeD {
        private String slot;

        void write(String v) {
          slot = v;
        }
      }
      """;

  @Test
  @DisplayName("two classes that declare a field of the same name are two reports")
  void twoClassesThatDeclareAFieldOfTheSameNameAreTwoReports() throws Exception {
    Map<String, String> sources = new LinkedHashMap<>();
    sources.put("ScopeA.java", NEVER_READ_A);
    sources.put("ScopeB.java", NEVER_READ_B);

    List<String> findings = run(sources, 0);

    assertThat(findings)
        .as(
            "the two classes declare two fields; keying a field by its NAME alone merges them, and"
                + " the rule can then report only one of the two")
        .hasSize(2);
    assertThat(findings.get(0)).contains("ScopeA.java").contains("'slot'");
    assertThat(findings.get(1)).contains("ScopeB.java").contains("'slot'");
  }

  @Test
  @DisplayName("a read of one class's field does not suppress another class's report")
  void aReadOfOneClasssFieldDoesNotSuppressAnotherClasssReport() throws Exception {
    Map<String, String> sources = new LinkedHashMap<>();
    sources.put("ScopeC.java", IS_READ_C);
    sources.put("ScopeD.java", NEVER_READ_D);

    List<String> findings = run(sources, 0);

    assertThat(findings)
        .as(
            "ScopeC reads its own field, so ScopeC's field is not reported; ScopeD's field of the"
                + " same name is a different field and nothing reads it, so it IS reported")
        .hasSize(1);
    assertThat(findings.get(0)).contains("ScopeD.java").contains("'slot'");
  }

  @Test
  @DisplayName("the reports are the same at every worker count")
  void theReportsAreTheSameAtEveryWorkerCount() throws Exception {
    Map<String, String> sources = new LinkedHashMap<>();
    sources.put("ScopeA.java", NEVER_READ_A);
    sources.put("ScopeB.java", NEVER_READ_B);

    List<String> sequential = run(sources, 0);

    for (int threads : new int[] {1, 2, 4}) {
      assertThat(run(sources, threads))
          .as("the two classes' fields must not be merged into one key by the worker count")
          .isEqualTo(sequential);
    }
  }

  /** Translates every source and runs the rule over the whole program. */
  private List<String> run(Map<String, String> sources, int threads) throws Exception {
    List<TacProgram> programs = new ArrayList<>();
    for (Map.Entry<String, String> entry : sources.entrySet()) {
      Path file = tmp.resolve(entry.getKey());
      Files.writeString(file, entry.getValue());
      ParsedSource ps = new JavaSourceParser().parse(file);
      programs.add(new TacTranslator(ps).translate());
    }
    EngineConfig config = ConfigLoader.loadFromClasspath();
    List<DfaChecker> checkers = List.of(new UnusedFieldChecker());
    // Each worker gets its OWN checkers: a checker carries per-function state, so a shared
    // instance would have one worker clearing another worker's state mid-walk.
    java.util.function.Supplier<List<DfaChecker>> factory = () -> List.of(new UnusedFieldChecker());
    FindingReporter reporter =
        new DfaEngine(config, checkers, "global", null, threads, factory)
            .runWithGlobalStore(programs, threads);
    List<String> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (!"dfa-taint-unused-private-field".equals(f.ruleId())) continue;
      out.add(f.ruleId() + "@" + f.file() + ":" + f.line() + " | " + f.message());
    }
    out.sort(String::compareTo);
    return out;
  }
}

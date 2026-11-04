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
import org.plumb.dfa.checkers.boundary.ArrayBoundsConstraintChecker;
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
 * Three false positives that the corpus grew, and the mechanism each one exposed.
 *
 * <p>They are grouped here because they share a shape: a rule reported a defect that the source
 * does not contain, and in each case the finding was <em>well-formed</em> -- a real rule, a real
 * sink, a real taint path. The output was not wrong; the QUESTION it answered was.
 *
 * <ul>
 *   <li>The command-injection rule reported a file write whose bytes were a literal, because the
 *       tainted value was the STREAM (the path it was opened with) rather than the data.
 *   <li>The format-string rule reported {@code String.format("%s", v)}, where the format string is
 *       a literal and only the format ARGUMENT is tainted.
 *   <li>The resource-leak rule reported a helper that returned a {@code String} as though it
 *       returned an unclosed resource.
 * </ul>
 */
class PrecisionRegressionTest {

  @TempDir Path tmp;

  private List<Finding> scan(String source, String name, DfaChecker... checkers) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(base, List.of(checkers), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    return new ArrayList<>(reporter.all());
  }

  // ── the tainted STREAM is not the tainted DATA ────────────────────────────────

  @Test
  @DisplayName("a write of literal bytes is not command injection, even when the stream is tainted")
  void taintedStreamIsNotCommandInjection() throws Exception {
    List<Finding> findings =
        scan(
            """
            import java.io.File;
            import java.io.FileOutputStream;
            import java.io.OutputStream;
            class T {
              void writeByName(String baseDir, String fileName) throws Exception {
                File out = new File(baseDir, fileName);
                try (OutputStream os = new FileOutputStream(out)) {
                  os.write("data".getBytes());
                }
              }
            }
            """,
            "T.java",
            new CommandInjectionChecker());
    assertThat(findings)
        .as(
            "the taint is on the STREAM (the path it was opened with); the bytes written are a"
                + " literal")
        .isEmpty();
  }

  @Test
  @DisplayName("a write of tainted bytes IS command injection")
  void taintedBytesAreCommandInjection() throws Exception {
    List<Finding> findings =
        scan(
            """
            import java.io.OutputStream;
            class T {
              void write(byte[] userInput) throws Exception {
                OutputStream os = System.out;
                os.write(userInput);
              }
            }
            """,
            "T.java",
            new CommandInjectionChecker());
    assertThat(findings)
        .as("the byte[] written is attacker-controlled, which is the CWE-78 shape")
        .isNotEmpty();
  }

  // ── the format ARGUMENT is not the FORMAT STRING ───────────────────────────────

  @Test
  @DisplayName("a literal format string with a tainted argument is not a format-string injection")
  void taintedFormatArgumentIsNotFormatStringInjection() throws Exception {
    List<Finding> findings =
        scan(
            """
            class T {
              String format(String v) {
                return String.format("%s", v);
              }
            }
            """,
            "T.java", new FormatStringChecker());
    assertThat(findings)
        .as(
            "the format string is a literal; only the format ARGUMENT is tainted, which is CWE-730,"
                + " not CWE-134")
        .isEmpty();
  }

  @Test
  @DisplayName("a tainted FORMAT STRING is a format-string injection")
  void taintedFormatStringIsFormatStringInjection() throws Exception {
    List<Finding> findings =
        scan(
            """
            class T {
              String formatTainted(String fmt) {
                return String.format(fmt);
              }
            }
            """,
            "T.java",
            new FormatStringChecker());
    assertThat(findings)
        .as("the attacker controls the format string itself, which is CWE-134")
        .isNotEmpty();
  }

  // ── a tainted String is not an unclosed resource ──────────────────────────────

  @Test
  @DisplayName("a helper that returns a tainted String does not leak a resource")
  void taintedStringReturnIsNotAResourceLeak() throws Exception {
    List<Finding> findings =
        scan(
            """
            class T {
              private String enrich(String userAgent, String path) {
                return "ua=" + userAgent + " path=" + path;
              }

              void handleRequest(String userAgent, String path) {
                String enriched = enrich(userAgent, path);
                use(enriched);
              }

              private void use(String s) {
                s.length();
              }
            }
            """,
            "T.java",
            new ResourceLeakChecker());
    assertThat(findings).as("java.lang.String has no close() and is not a resource").isEmpty();
  }

  // ── a disjunction's FALSE edge proves the negated bounds ──────────────────────

  @Test
  @DisplayName(
      "an early return on a disjunction proves the negated bounds on the fall-through path")
  void earlyReturnOnDisjunctionProvesTheNegatedBounds() throws Exception {
    List<Finding> findings =
        scan(
            """
            class T {
              String safeAfterGuard(int index) {
                String[] arr = new String[3];
                if (index < 0 || index > 2) {
                  return null;
                }
                return arr[index];
              }
            }
            """,
            "T.java",
            new ArrayBoundsConstraintChecker(),
            new ArrayIndexChecker());
    assertThat(findings)
        .as("index is in [0,2] and the array has length 3, so the access is in range")
        .isEmpty();
  }

  @Test
  @DisplayName("the same shape without the guard is still reported")
  void unguardedAccessIsStillReported() throws Exception {
    List<Finding> findings =
        scan(
            """
            class T {
              String unsafeWithoutGuard(int index) {
                String[] arr = new String[3];
                return arr[index];
              }
            }
            """,
            "T.java",
            new ArrayBoundsConstraintChecker(),
            new ArrayIndexChecker());
    assertThat(findings).as("without the guard the index really can be out of range").isNotEmpty();
  }
}

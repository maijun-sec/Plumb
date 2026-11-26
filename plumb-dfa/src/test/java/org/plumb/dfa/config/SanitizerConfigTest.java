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
package org.plumb.dfa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The project's own sanitizers: the trust boundaries the engine cannot know by itself.
 *
 * <h2>What this pins</h2>
 *
 * <p>The engine has a built-in list of sanitizers -- the standard library's numeric parsers, the
 * cryptographic boundary. A project has trust boundaries of its own: its own escaping helper, its
 * own validator. Before this table existed, such a method could not be declared at all, and the
 * taint it was supposed to remove reached the sink.
 *
 * <p>Each case below is a witness for the DECLARATION, and for nothing else: no other case in this
 * file declares a sanitizer.
 */
@DisplayName("The project's own sanitizers can be declared")
class SanitizerConfigTest {

  private static final String RULE = "dfa-taint-sql-injection";

  @TempDir Path tmp;

  /**
   * A program whose own method cleans the value before the sink.
   *
   * <p>The cleaning method is the project's own, so the engine has no way of knowing it is clean
   * unless the project says so.
   */
  private static final String PROJECT_SANITIZER =
      """
      class OwnSanitizer {
        static String clean(String raw) {
          return raw.replace("<", "").replace(">", "");
        }

        void go(String userInput, java.sql.Statement st) throws Exception {
          String cleaned = clean(userInput);
          st.executeQuery("SELECT * FROM t WHERE c = '" + cleaned + "'");
        }
      }
      """;

  /**
   * The same program, without the project declaring anything.
   *
   * <p>This is the control. Without it, a test that asserts "no finding" would pass just as well on
   * a program where the taint never reached the sink in the first place -- the assertion would be
   * measuring the fixture, not the mechanism.
   */
  private static final String NO_DECLARATION =
      """
      class OwnSanitizer {
        static String clean(String raw) {
          return raw.replace("<", "").replace(">", "");
        }

        void go(String userInput, java.sql.Statement st) throws Exception {
          String cleaned = clean(userInput);
          st.executeQuery("SELECT * FROM t WHERE c = '" + cleaned + "'");
        }
      }
      """;

  @Test
  @DisplayName("a declared sanitizer removes the taint")
  void aDeclaredSanitizerRemovesTheTaint() throws Exception {
    EngineConfig config = withSanitizers("^(?:[\\w.]+\\.)?clean/1$");

    assertThat(findings(config, PROJECT_SANITIZER))
        .as(
            "the project declared the method a sanitizer, so the value that comes out of it is "
                + "clean and cannot reach the sink")
        .isZero();
  }

  @Test
  @DisplayName("without the declaration the taint reaches the sink")
  void withoutTheDeclarationTheTaintReachesTheSink() throws Exception {
    assertThat(findings(ConfigLoader.loadFromClasspath(), NO_DECLARATION))
        .as(
            "the control: the same program under the default configuration still reports, so the "
                + "assertion above is measuring the declaration and not the fixture")
        .isPositive();
  }

  @Test
  @DisplayName("the table matches the callee signature as the engine records it")
  void theTableMatchesTheCalleeSignatureAsTheEngineRecordsIt() throws Exception {
    EngineConfig config = withSanitizers("^(?:[\\w.]+\\.)?clean/1$");
    SanitizerConfig table = config.sanitizers();

    assertThat(table.isSanitizer("clean/1"))
        .as("an unqualified call is recorded without its owner")
        .isTrue();
    assertThat(table.isSanitizer("OwnSanitizer.clean/1"))
        .as("a qualified call is recorded with its owner")
        .isTrue();
    assertThat(table.isSanitizer("other/1")).as("a different method is not a sanitizer").isFalse();
    assertThat(table.isSanitizer("java.lang.Integer.parseInt/1"))
        .as(
            "the project's table is a table of its own: it does not answer for the built-in list, "
                + "which the engine consults separately")
        .isFalse();
  }

  @Test
  @DisplayName("an entry that is turned off does not match")
  void anEntryThatIsTurnedOffDoesNotMatch() throws Exception {
    EngineConfig config =
        withSanitizers(
            "^(?:[\\w.]+\\.)?clean/1$",
            "sanitizers:\n"
                + "  - id: on\n"
                + "    match: '^(?:[\\w.]+\\.)?clean/1$'\n"
                + "  - id: off\n"
                + "    match: '.*'\n"
                + "    enabled: false\n");

    assertThat(config.sanitizers().isSanitizer("clean/1"))
        .as("the enabled entry still matches")
        .isTrue();
    assertThat(config.sanitizers().isSanitizer("anything/1"))
        .as("the turned-off entry does not match, even though its pattern would")
        .isFalse();
  }

  @Test
  @DisplayName("an entry without a match is skipped, not guessed at")
  void anEntryWithoutAMatchIsSkippedNotGuessedAt() throws Exception {
    EngineConfig config = withSanitizers("x", "sanitizers:\n  - id: nameless\n");
    assertThat(config.sanitizers().entries())
        .as("a sanitizer with no pattern would silence every taint rule, so it is skipped")
        .isEmpty();
    assertThat(config.sanitizers().isSanitizer("anything/1")).isFalse();
  }

  // ─── the machinery ───────────────────────────────────────────────────

  /** An engine configuration whose sanitizer table holds one pattern. */
  private EngineConfig withSanitizers(String match) throws Exception {
    return withSanitizers(match, "sanitizers:\n  - id: declared\n    match: '" + match + "'\n");
  }

  private EngineConfig withSanitizers(String match, String yaml) throws Exception {
    Path file = tmp.resolve("sanitizers-" + Math.abs(match.hashCode()) + ".yaml");
    Files.writeString(file, yaml, StandardCharsets.UTF_8);
    return ConfigLoader.loadFromClasspath(Map.of("sanitizers.yaml", file));
  }

  /** How many findings of the rule the engine produces. */
  private int findings(EngineConfig config, String source) throws Exception {
    Path file = tmp.resolve("OwnSanitizer.java");
    Files.writeString(file, source, StandardCharsets.UTF_8);
    ParsedSource parsed = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(parsed).translate();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new SqlInjectionChecker()), "cha", null).run(program);
    int count = 0;
    for (Finding f : reporter.all()) {
      if (RULE.equals(f.ruleId())) {
        count++;
      }
    }
    return count;
  }
}

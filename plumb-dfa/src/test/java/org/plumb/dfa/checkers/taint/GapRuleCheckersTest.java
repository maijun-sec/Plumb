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
 * The two gap rules that were registered but had no checker: unrestricted file access (CWE-732) and
 * regex injection (CWE-1333).
 *
 * <p>CWE-1333 (regular expression injection) and CWE-732 (unrestricted file access) were both in
 * the rule catalog with no checker behind them, so neither could ever be reported. These tests
 * check that they fire now -- and, just as importantly, that they stay quiet on the safe forms.
 */
class GapRuleCheckersTest {

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

  // ─── CWE-1333: regex injection ──────────────────────────────────────

  @Test
  @DisplayName("a tainted pattern reaches Pattern.compile and fires")
  void taintedPatternFires() throws Exception {
    List<Finding> findings =
        scan(
            """
            import java.util.regex.Pattern;
            class T {
              void go(String userInput) {
                Pattern p = Pattern.compile(userInput);
                p.matcher("x").matches();
              }
            }
            """,
            "T.java",
            new RegexInjectionChecker());
    assertThat(findings).as("a user-controlled regex pattern is CWE-1333").isNotEmpty();
    assertThat(findings.get(0).cwe()).isEqualTo("CWE-1333");
  }

  @Test
  @DisplayName("a constant pattern does not fire")
  void constantPatternIsSilent() throws Exception {
    List<Finding> findings =
        scan(
            """
            import java.util.regex.Pattern;
            class T {
              void go() {
                Pattern p = Pattern.compile("[a-z]+");
                p.matcher("x").matches();
              }
            }
            """,
            "T2.java",
            new RegexInjectionChecker());
    assertThat(findings).as("a constant pattern is not a defect").isEmpty();
  }

  @Test
  @DisplayName("the rule watches ONE family, whose language table holds the per-language sinks")
  void ruleWatchesOneFamilyWithALanguageTable() {
    RegexInjectionChecker checker = new RegexInjectionChecker();
    List<String> families = checker.rule().sinkFamilyKeys();
    assertThat(families)
        .as("CWE-1333 is ONE defect class; the per-language symbols live in the family's table")
        .containsExactly("regex.sinks");
  }

  @Test
  @DisplayName("the rule matches its own family and no other")
  void multiFamilyRuleMatchesEachFamily() {
    RegexInjectionChecker checker = new RegexInjectionChecker();
    assertThat(checker.rule().matchesSinkFamily("regex.sinks")).isTrue();
    assertThat(checker.rule().matchesSinkFamily("sql.sinks")).isFalse();
  }

  // ─── CWE-732: unrestricted file access ───────────────────────────────

  @Test
  @DisplayName("setReadable(true) grants everyone and fires")
  void readableForEveryoneFires() throws Exception {
    List<Finding> findings =
        scan(
            """
            import java.io.File;
            class T {
              void go(File f) {
                f.setReadable(true);
              }
            }
            """,
            "T3.java",
            new FilePermissionChecker());
    assertThat(findings).as("setReadable(true) grants the permission to everyone").isNotEmpty();
    assertThat(findings.get(0).cwe()).isEqualTo("CWE-732");
  }

  @Test
  @DisplayName("setReadable(true, true) restricts to the owner and does not fire")
  void readableForOwnerOnlyIsSilent() throws Exception {
    List<Finding> findings =
        scan(
            """
            import java.io.File;
            class T {
              void go(File f) {
                f.setReadable(true, true);
              }
            }
            """,
            "T4.java",
            new FilePermissionChecker());
    assertThat(findings).as("ownerOnly = true is the safe form").isEmpty();
  }

  @Test
  @DisplayName("setReadable(false) removes the permission and does not fire")
  void readableFalseIsSilent() throws Exception {
    List<Finding> findings =
        scan(
            """
            import java.io.File;
            class T {
              void go(File f) {
                f.setReadable(false);
              }
            }
            """,
            "T5.java",
            new FilePermissionChecker());
    assertThat(findings).as("revoking a permission is not a defect").isEmpty();
  }

  @Test
  @DisplayName("setWritable(true) fires too")
  void writableForEveryoneFires() throws Exception {
    List<Finding> findings =
        scan(
            """
            import java.io.File;
            class T {
              void go(File f) {
                f.setWritable(true);
              }
            }
            """,
            "T6.java",
            new FilePermissionChecker());
    assertThat(findings).as("setWritable(true) is the same defect").isNotEmpty();
  }

  @Test
  @DisplayName("an unrelated call does not fire")
  void unrelatedCallIsSilent() throws Exception {
    List<Finding> findings =
        scan(
            """
            import java.io.File;
            class T {
              void go(File f) {
                f.getName();
                f.exists();
              }
            }
            """,
            "T7.java",
            new FilePermissionChecker());
    assertThat(findings).as("reading a file grants nothing").isEmpty();
  }
}

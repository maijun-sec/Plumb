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
/*
 * R787b unit tests for plumb-style InfiniteLoopCheck.
 *
 * <p>Mirrors the 10 cases from
 * {@code plumb-dfa/src/test/java/.../R774InfiniteLoopCheckerTest.java}
 * but drives the rule through {@link
 * org.plumb.style.AstDispatcher} (the plumb-style single-pass
 * runner) instead of {@link
 * org.plumb.dfa.engine.DfaEngine}. The detection logic is
 * unchanged (R774 two-strategy detection: constant-true
 * condition, value-bounded modulo proof), only the
 * registration path moved from dfa to style.</p>
 *
 * <p>The dfa-side test is preserved as a @Disabled file so
 * the design history is auditable. This file is the
 * canonical test for the rule going forward.</p>
 */
package org.plumb.style.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstDispatcher;
import org.plumb.style.StyleContext;

class R787bInfiniteLoopCheckTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private static List<Finding> run(String name, String sourceText) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of(name), sourceText);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    AstDispatcher d = new AstDispatcher().register(new InfiniteLoopCheck());
    d.run(ctx);
    return ctx.findings();
  }

  private static long countCwe835(List<Finding> findings) {
    // R787b: style-emitted findings store the canonical
    // RuleDef; the ruleId() getter returns
    // RuleDef.ruleId().id() (== "ast-style-infinite-loop"). The CWE
    // string tag ("CWE-835") lives on the RuleDef, not
    // on Finding.cwe() (which is null for the
    // RuleDef-backed emission path). The eval harness
    // (eval_findings_cwe_matched.py) keys off
    // f.ruleId() and looks up CWE via
    // plumb_rule_to_cwe.json, which is exactly the
    // shape we want.
    return findings.stream().filter(f -> "ast-style-infinite-loop".equals(f.ruleId())).count();
  }

  // ─────── Constant-true detection (R759-E regression) ───────

  @Test
  void constantTrueWhileFires() {
    String src =
        """
        class Cwe835 {
            public void bad() {
                int i = 0;
                while (true) {
                    i++;
                    if (i > 100) {
                        // no break; infinite
                    }
                }
            }
        }
        """;
    List<Finding> findings = run("ConstantTrueWhile.java", src);
    assertThat(countCwe835(findings)).isEqualTo(1L);
  }

  @Test
  void constantTrueWhileWithBreakDoesNotFire() {
    // while (true) with a reachable break must NOT fire.
    String src =
        """
        class Cwe835 {
            public void good() {
                int i = 0;
                while (true) {
                    i++;
                    if (i > 100) break;
                }
            }
        }
        """;
    List<Finding> findings = run("ConstantTrueWhileBreak.java", src);
    assertThat(countCwe835(findings))
        .as("while(true) with reachable break should not fire")
        .isZero();
  }

  @Test
  void constantTrueForFires() {
    String src =
        """
        class Cwe835 {
            public void bad() {
                for (;;) {
                    // no break
                    int i = 0;
                    i++;
                }
            }
        }
        """;
    List<Finding> findings = run("ConstantTrueFor.java", src);
    assertThat(countCwe835(findings)).isEqualTo(1L);
  }

  @Test
  void constantTrueDoWhileFires() {
    String src =
        """
        class Cwe835 {
            public void bad() {
                do {
                    int i = 0;
                    i++;
                } while (true);
            }
        }
        """;
    List<Finding> findings = run("ConstantTrueDoWhile.java", src);
    assertThat(countCwe835(findings)).isEqualTo(1L);
  }

  // ─────── Value-bounded modulo detection (R774) ───────

  @Test
  void while01ValueBoundedFires() {
    // Juliet CWE835__while_01 bad() body
    String src =
        """
        class Cwe835While {
            public void bad() {
                int i = 0;
                while (i >= 0) {
                    IO.writeLine(i);
                    i = (i + 1) % 256;
                }
            }
        }
        """;
    List<Finding> findings = run("Cwe835While01.java", src);
    assertThat(countCwe835(findings)).isEqualTo(1L);
  }

  @Test
  void for01ValueBoundedFires() {
    // Juliet CWE835__for_01 bad() body - modulo in step clause
    String src =
        """
        class Cwe835For {
            public void bad() {
                for (int i = 0; i >= 0; i = (i + 1) % 256) {
                    IO.writeLine(i);
                }
            }
        }
        """;
    List<Finding> findings = run("Cwe835For01.java", src);
    assertThat(countCwe835(findings)).isEqualTo(1L);
  }

  @Test
  void do01ValueBoundedFires() {
    // Juliet CWE835__do_01 bad() body
    String src =
        """
        class Cwe835Do {
            public void bad() {
                int i = 0;
                do {
                    IO.writeLine(i);
                    i = (i + 1) % 256;
                } while (i >= 0);
            }
        }
        """;
    List<Finding> findings = run("Cwe835Do01.java", src);
    assertThat(countCwe835(findings)).isEqualTo(1L);
  }

  // ─────── Negative cases (must NOT fire) ───────

  @Test
  void good1WithBreakDoesNotFire() {
    // Juliet CWE835__while_01 good1() body
    String src =
        """
        class Cwe835Good {
            public void good() {
                int i = 0;
                while (i >= 0) {
                    if (i == 10) { break; }
                    IO.writeLine(i);
                    i = (i + 1) % 256;
                }
            }
        }
        """;
    List<Finding> findings = run("Cwe835Good1.java", src);
    assertThat(countCwe835(findings)).as("break in body should suppress").isZero();
  }

  @Test
  void withoutModuloDoesNotFire() {
    // No modulo update - no value-bounded proof
    String src =
        """
        class Cwe835NonModulo {
            public void m() {
                int i = 0;
                while (i >= 0) {
                    IO.writeLine(i);
                    i++;
                }
            }
        }
        """;
    List<Finding> findings = run("Cwe835NonModulo.java", src);
    assertThat(countCwe835(findings)).as("no modulo = no value-bounded proof").isZero();
  }

  @Test
  void differentConditionDoesNotFire() {
    // i >= 5 - not permanently true under modulo [0, N-1]
    String src =
        """
        class Cwe835GtFive {
            public void m() {
                int i = 0;
                while (i >= 5) {
                    IO.writeLine(i);
                    i = (i + 1) % 256;
                }
            }
        }
        """;
    List<Finding> findings = run("Cwe835GtFive.java", src);
    assertThat(countCwe835(findings)).as("i >= 5 is not 'permanently true' under [0,N-1]").isZero();
  }

  @Test
  void moduloOnDifferentVarDoesNotFire() {
    // Modulo is on j, condition is on i - mismatch
    String src =
        """
        class Cwe835Mismatch {
            public void m() {
                int i = 0, j = 0;
                while (i >= 0) {
                    IO.writeLine(i);
                    j = (j + 1) % 256;
                }
            }
        }
        """;
    List<Finding> findings = run("Cwe835Mismatch.java", src);
    assertThat(countCwe835(findings)).as("modulo on j, not the loop var i").isZero();
  }

  @Test
  void fieldAccessConditionDoesNotFire() {
    // Field access on LHS returns null from
    // inductionVarForGeZero (only matches simple
    // NameExpr), so the rule conservatively does not
    // fire on field-bound loops.
    String src =
        """
        class Cwe835Field {
            static int counter = 0;
            public void m() {
                while (Cwe835Field.counter >= 0) {
                    IO.writeLine(counter);
                    counter = (counter + 1) % 256;
                }
            }
        }
        """;
    List<Finding> findings = run("Cwe835Field.java", src);
    assertThat(countCwe835(findings))
        .as("field access condition: AST returns null, conservative")
        .isZero();
  }

  // ─────── Style integration: fires under plumb-style rule id ───────

  @Test
  void firesWithCwe835RuleId() {
    // R787b: the rule's RuleDef.ruleId().id() is "ast-style-infinite-loop"
    // (not "plumb.dfa.cwe835-infinite-loop"). The eval
    // harness's plumb_rule_to_cwe.json has
    // `Cwe835 → ["835"]`, so emitting under this id is
    // what lets strict eval hit CWE-835 instead of
    // cwe-mismatch-skipping (Lesson 1154).
    String src =
        """
        class Cwe835RuleId {
            public void bad() {
                while (true) {
                    int i = 0;
                    i++;
                }
            }
        }
        """;
    List<Finding> findings = run("Cwe835RuleId.java", src);
    assertThat(findings).isNotEmpty();
    Finding f = findings.get(0);
    // Style-emitted findings store the canonical
    // RuleDef; the ruleId() getter returns the
    // RuleDef.ruleId().id() (so == "ast-style-infinite-loop"). The
    // CWE tag is sourced from RuleDef.cwe() at
    // NDJSON-emit time, not from Finding.cwe()
    // (which is null for the RuleDef-backed path).
    assertThat(f.ruleId()).isEqualTo("ast-style-infinite-loop");
    assertThat(f.rule()).isNotNull();
    assertThat(f.rule().cwe()).containsExactly("CWE-835");
  }
}

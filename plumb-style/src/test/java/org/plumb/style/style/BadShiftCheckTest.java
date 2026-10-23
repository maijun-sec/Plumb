/*
 * Hermetic unit test for BadShiftCheck (R-812 migration).
 *
 * <p>Verifies the four CWE-758 patterns:</p>
 *
 * <ol>
 *   <li>{@code 1 << 32} fires (count &ge; int bit width).</li>
 *   <li>{@code 1L << 64} fires (count &ge; long bit width).</li>
 *   <li>{@code 1 << -1} fires (negative count).</li>
 *   <li>{@code 1 << 5} does NOT fire (safe).</li>
 *   <li>{@code x << 2} does NOT fire (variable count ? DFA
 *       half would have unwrapped a single MOVE / NEG;
 *       the AST half is conservative and skips).</li>
 * </ol>
 */
package org.plumb.style.style;

import org.plumb.rules.RuleDef;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.StyleContext;

class BadShiftCheckTest {

  private final BadShiftCheck check = new BadShiftCheck();
  private final ParserConfiguration cfg =
      new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  private final JavaParser parser = new JavaParser(cfg);

  /** Drive the check over a single-class snippet, return every finding the visit emits. */
  private List<Finding> run(String src) {
    CompilationUnit cu = parser.parse(src).getResult().get();
    ParsedSource ps = new ParsedSource(Path.of("Test.java"), src, cu, List.<Problem>of());
    RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);
    StyleContext ctx = new StyleContext(ps, rules);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new org.plumb.style.AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }

  @Test
  void intShiftBy32_fires() {
    // 1 << 32 on int — count equals bit width, masked
    // to 0 at runtime, almost always a bug.
    List<Finding> findings = run("class T { void m() { int x = 1 << 32; } }");
    assertThat(findings).as("1 << 32 on int should fire CWE-758").hasSize(1);
    assertThat(findings.get(0).cwe())
        .as("RuleDef cwe is propagated through to " + "Finding (R-812 cwe passthrough fix)")
        .isEqualTo("CWE-758");
  }

  @Test
  void longShiftBy64_fires() {
    // 1L << 64 on long — same idea, long bit width is 64.
    List<Finding> findings = run("class T { void m() { long x = 1L << 64; } }");
    assertThat(findings).as("1L << 64 on long should fire CWE-758").hasSize(1);
  }

  @Test
  void negativeShift_fires() {
    // 1 << -1 — throws ArithmeticException at runtime.
    // AST sees the literal -1, fires.
    List<Finding> findings = run("class T { void m() { int x = 1 << -1; } }");
    assertThat(findings).as("1 << -1 should fire CWE-758 (negative count)").hasSize(1);
  }

  @Test
  void safeShift_doesNotFire() {
    // 1 << 5 — well within int range.
    List<Finding> findings = run("class T { void m() { int x = 1 << 5; } }");
    assertThat(findings).as("1 << 5 is safe; the rule must not fire").isEmpty();
  }

  @Test
  void variableShift_doesNotFire() {
    // x << 2 — count is a variable. The DFA half would
    // have unwrapped a single MOVE. The AST half is
    // conservative: skip.
    List<Finding> findings = run("class T { void m() { int n = 2; int x = 1 << n; } }");
    assertThat(findings)
        .as(
            "Variable shift count is out of scope for the "
                + "AST half (would need DFA / range analysis).")
        .isEmpty();
  }

  @Test
  void unsignedRightShift_fires() {
    // 1 >>> 32 — same as << 32 on int. AST should fire
    // for all three shift operators.
    List<Finding> findings = run("class T { void m() { int x = 1 >>> 32; } }");
    assertThat(findings).as("1 >>> 32 should fire CWE-758").hasSize(1);
  }
}

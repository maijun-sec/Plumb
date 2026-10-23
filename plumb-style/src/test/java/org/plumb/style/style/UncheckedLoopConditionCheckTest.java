/*
 * R-812 Batch 3 ? hermetic test for
 * {@link UncheckedLoopConditionCheck}.
 */
package org.plumb.style.style;

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

class UncheckedLoopConditionCheckTest {

  private final UncheckedLoopConditionCheck check = new UncheckedLoopConditionCheck();
  private final ParserConfiguration cfg =
      new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  private final JavaParser parser = new JavaParser(cfg);

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
  void whileTrueNoBreak_fires() {
    List<Finding> f =
        run("class T { void m() { " + "int i = 0; " + "while (true) { i++; } " + "} }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-674");
  }

  @Test
  void whileTrueWithBreak_doesNotFire() {
    List<Finding> f =
        run(
            "class T { void m() { "
                + "int i = 0; "
                + "while (true) { i++; if (i > 100) break; } "
                + "} }");
    assertThat(f).isEmpty();
  }

  @Test
  void whileTrueWithReturn_doesNotFire() {
    List<Finding> f =
        run(
            "class T { void m() { "
                + "int i = 0; "
                + "while (true) { i++; if (i > 100) return; } "
                + "} }");
    assertThat(f).isEmpty();
  }

  @Test
  void forEmptyNoBreak_fires() {
    List<Finding> f = run("class T { void m() { " + "for (;;) { int i = 0; i++; } " + "} }");
    assertThat(f).hasSize(1);
  }

  @Test
  void forEmptyWithBreak_doesNotFire() {
    List<Finding> f = run("class T { void m() { " + "for (;;) { break; } " + "} }");
    assertThat(f).isEmpty();
  }

  @Test
  void doWhileTrueNoBreak_fires() {
    List<Finding> f =
        run("class T { void m() { " + "int i = 0; " + "do { i++; } while (true); " + "} }");
    assertThat(f).hasSize(1);
  }

  @Test
  void doWhileTrueWithBreak_doesNotFire() {
    List<Finding> f =
        run(
            "class T { void m() { "
                + "int i = 0; "
                + "do { i++; if (i > 100) break; } while (true); "
                + "} }");
    assertThat(f).isEmpty();
  }

  @Test
  void whileFalse_doesNotFire() {
    // while(false) — not constant-true.
    List<Finding> f = run("class T { void m() { " + "while (false) { } " + "} }");
    assertThat(f).isEmpty();
  }
}

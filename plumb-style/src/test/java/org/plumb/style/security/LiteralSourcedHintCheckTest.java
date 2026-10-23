/*
 * R-812 Batch 3 ? hermetic test for
 * {@link LiteralSourcedHintCheck}.
 */
package org.plumb.style.security;

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

class LiteralSourcedHintCheckTest {

  private final LiteralSourcedHintCheck check = new LiteralSourcedHintCheck();
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
  void sensitiveNameLongLiteral_fires() {
    List<Finding> f =
        run("class T { void m() { " + "String password = \"hunter2-secret-value\"; " + "} }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-798");
    assertThat(f.get(0).ruleId()).isEqualTo("ast-security-literal-sourced-hint");
  }

  @Test
  void genericNameLongLiteral_fires() {
    // Non-sensitive name but long literal still fires
    // (INFO severity, generic rule id).
    List<Finding> f =
        run("class T { void m() { " + "String config = \"this-is-a-long-string\"; " + "} }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).ruleId()).isEqualTo("dfa-security-literal-sourced-generic");
  }

  @Test
  void shortLiteral_doesNotFire() {
    // 4-char literal — below MIN_LENGTH.
    List<Finding> f = run("class T { void m() { " + "String password = \"abcd\"; " + "} }");
    assertThat(f).isEmpty();
  }

  @Test
  void placeholder_doesNotFire() {
    List<Finding> f =
        run("class T { void m() { " + "String password = \"<your-password>\"; " + "} }");
    // "<your-password>" length > MIN_LENGTH but matches
    // placeholder filter — should NOT fire.
    assertThat(f).isEmpty();
  }

  @Test
  void nonStringInit_doesNotFire() {
    // Init is an int literal, not a string — skip.
    List<Finding> f = run("class T { void m() { " + "int count = 123456789; " + "} }");
    assertThat(f).isEmpty();
  }
}

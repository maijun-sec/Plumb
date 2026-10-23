/*
 * R-812 Batch 3 ? hermetic test for
 * {@link UncheckedReturnCheck}.
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
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

class UncheckedReturnCheckTest {
  private final AstChecker check = new UncheckedReturnCheck();
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
  void fileDeleteStatement_fires() {
    List<Finding> f = run("class T { void m(java.io.File f) { f.delete(); } }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-252");
  }

  @Test
  void inputStreamCloseStatement_fires() {
    List<Finding> f =
        run("class T { void m(java.io.InputStream s) throws Exception { " + "s.close(); " + "} }");
    assertThat(f).hasSize(1);
  }

  @Test
  void mkdirStatement_fires() {
    List<Finding> f = run("class T { void m(java.io.File f) { f.mkdir(); } }");
    assertThat(f).hasSize(1);
  }

  @Test
  void assignedReturn_doesNotFire() {
    // `boolean ok = f.delete();` — return is captured.
    List<Finding> f =
        run("class T { void m(java.io.File f) { " + "boolean ok = f.delete(); " + "} }");
    assertThat(f).isEmpty();
  }

  @Test
  void comparedReturn_doesNotFire() {
    // `if (f.delete()) { ... }` — return is used.
    List<Finding> f = run("class T { void m(java.io.File f) { " + "if (f.delete()) { } " + "} }");
    assertThat(f).isEmpty();
  }

  @Test
  void unrelatedMethodStatement_doesNotFire() {
    // `System.out.println(...)` — not on the
    // must-check list.
    List<Finding> f = run("class T { void m() { " + "System.out.println(\"hi\"); " + "} }");
    assertThat(f).isEmpty();
  }
}

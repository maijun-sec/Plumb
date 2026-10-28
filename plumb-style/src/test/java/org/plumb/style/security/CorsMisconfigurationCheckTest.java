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
 * R-812 Batch 3 ? hermetic test for
 * {@link CorsMisconfigurationCheck}.
 *
 * <p>Both imperative and annotation forms fire CWE-942
 * on a wildcard origin. Specific origins do NOT fire.</p>
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

class CorsMisconfigurationCheckTest {

  private final AstChecker check = new CorsMisconfigurationCheck();
  private final ParserConfiguration cfg =
      new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  private final JavaParser parser = new JavaParser(cfg);

  /**
   * Drive the check via {@link org.plumb.style.AstDispatcher}. The dispatcher walks the unit once
   * and calls the checker's per-type visit overloads (MethodCallExpr, NormalAnnotationExpr,
   * SingleMemberAnnotationExpr). Per-type dispatch makes the old manual split between imperative
   * (MethodCallExpr) and annotation forms unnecessary.
   */
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
  void imperativeAddAllowedOriginStar_fires() {
    List<Finding> f =
        run(
            "class T { void m() { "
                + "org.springframework.web.cors.CorsConfiguration cfg = "
                + "new org.springframework.web.cors.CorsConfiguration(); "
                + "cfg.addAllowedOrigin(\"*\"); "
                + "} }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-942");
  }

  @Test
  void imperativeListOfStar_fires() {
    List<Finding> f =
        run(
            "class T { void m() { "
                + "java.util.List<String> origins = java.util.List.of(\"*\"); "
                + "} }");
    // List.of("*") is not a Cors method call — only fires
    // when the assignment passes through a Cors method.
    // The test below covers that case.
    assertThat(f).isEmpty();

    List<Finding> f2 =
        run(
            "class T { void m() { "
                + "org.springframework.web.cors.CorsConfiguration cfg = "
                + "new org.springframework.web.cors.CorsConfiguration(); "
                + "cfg.setAllowedOrigins(java.util.List.of(\"*\")); "
                + "} }");
    assertThat(f2).hasSize(1);
  }

  @Test
  void annotationAllowedOriginsStar_fires() {
    List<Finding> f =
        run(
            "@org.springframework.web.bind.annotation.CrossOrigin("
                + "allowedOrigins = \"*\") "
                + "class T { }");
    assertThat(f).hasSize(1);
  }

  @Test
  void annotationOriginsStar_fires() {
    // `@CrossOrigin(origins = "*")` — legacy alias.
    List<Finding> f =
        run(
            "@org.springframework.web.bind.annotation.CrossOrigin("
                + "origins = \"*\") "
                + "class T { }");
    assertThat(f).hasSize(1);
  }

  @Test
  void imperativeSpecificOrigin_doesNotFire() {
    List<Finding> f =
        run(
            "class T { void m() { "
                + "org.springframework.web.cors.CorsConfiguration cfg = "
                + "new org.springframework.web.cors.CorsConfiguration(); "
                + "cfg.addAllowedOrigin(\"https://example.com\"); "
                + "} }");
    assertThat(f).isEmpty();
  }

  @Test
  void annotationSpecificOrigin_doesNotFire() {
    List<Finding> f =
        run(
            "@org.springframework.web.bind.annotation.CrossOrigin("
                + "allowedOrigins = \"https://example.com\") "
                + "class T { }");
    assertThat(f).isEmpty();
  }
}

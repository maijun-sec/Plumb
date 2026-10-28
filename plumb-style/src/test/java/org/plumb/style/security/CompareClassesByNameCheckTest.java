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
 * {@link CompareClassesByNameCheck}.
 *
 * <p>The check fires on
 * {@code a.getClass() == b.getClass()} /
 * {@code a.getClass() == X.class} ? the
 * canonical compare-by-reference pattern.
 * {@code .equals(...)} and unrelated equality checks
 * do NOT fire.</p>
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

class CompareClassesByNameCheckTest {
  private final AstChecker check = new CompareClassesByNameCheck();
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
  void getClassEqualsGetClass_fires() {
    List<Finding> f =
        run(
            "class T { void m(Object a, Object b) { "
                + "if (a.getClass() == b.getClass()) { "
                + "System.out.println(\"same\"); "
                + "} } }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-273");
  }

  @Test
  void getClassEqualsClassLiteral_fires() {
    List<Finding> f =
        run(
            "class T { void m(Object a) { "
                + "if (a.getClass() == String.class) { "
                + "System.out.println(\"string\"); "
                + "} } }");
    assertThat(f).hasSize(1);
  }

  @Test
  void getClassEqualsDotEquals_doesNotFire() {
    // `.equals(...)` is the recommended form. NOT a
    // binary == operator; must not fire.
    List<Finding> f =
        run(
            "class T { void m(Object a, Object b) { "
                + "if (a.getClass().equals(b.getClass())) { "
                + "System.out.println(\"same\"); "
                + "} } }");
    assertThat(f).isEmpty();
  }

  @Test
  void unrelatedEquals_doesNotFire() {
    // Two strings compared with == — not getClass.
    List<Finding> f =
        run(
            "class T { void m(String a, String b) { "
                + "if (a == b) { System.out.println(\"same\"); } } }");
    assertThat(f).isEmpty();
  }

  @Test
  void getClassInEqualsWithNonClassLiteral_doesNotFire() {
    // getClass() == "foo" is a useless compare but the
    // rule only fires on getClass() vs getClass() / .class.
    List<Finding> f = run("class T { void m(Object a) { " + "if (a.getClass() == \"foo\") { } } }");
    assertThat(f).isEmpty();
  }
}

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
 * {@link SuspiciousCommentCheck}.
 *
 * <p>The plumb-style
 * {@link org.plumb.style.style.SuspiciousCommentCheck}
 * was originally added by R725-C1 with the BUG / FIXME /
 * HACK / LATER / TODO keyword list. The plumb-dfa
 * half (R-812 source {@code
 * plumb-dfa/.../checkers/SuspiciousCommentChecker.java})
 * carried a richer keyword list (XXX / WORKAROUND /
 * TODO: / TBD: / SECURITY: / VULN / CVE / INJECTION)
 * tuned by AI dedup. The tests below exercise the
 * plumb-style version's current keyword list.</p>
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

class SuspiciousCommentCheckTest {

  private final SuspiciousCommentCheck check = new SuspiciousCommentCheck();
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
  void fixmeComment_fires() {
    List<Finding> f =
        run("class T { void m() { " + "// FIXME: handle null better\n" + "int x = 1; " + "} }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-546");
  }

  @Test
  void todoComment_fires() {
    List<Finding> f = run("class T { void m() { " + "// TODO: refactor\n" + "int x = 1; " + "} }");
    assertThat(f).hasSize(1);
  }

  @Test
  void hackComment_fires() {
    List<Finding> f =
        run(
            "class T { void m() { "
                + "// HACK: bypass CSP for legacy clients\n"
                + "int x = 1; "
                + "} }");
    assertThat(f).hasSize(1);
  }

  @Test
  void benignComment_doesNotFire() {
    List<Finding> f =
        run("class T { void m() { " + "// a normal descriptive comment\n" + "int x = 1; " + "} }");
    assertThat(f).isEmpty();
  }

  @Test
  void wordInsideLine_doesNotFire() {
    // The keyword must be at the start of the comment
    // body (after optional leading whitespace). A word
    // embedded mid-line doesn't fire.
    List<Finding> f =
        run("class T { void m() { " + "// not really a bug\n" + "int x = 1; " + "} }");
    assertThat(f).isEmpty();
  }
}

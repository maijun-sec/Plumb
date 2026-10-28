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
 * {@link HighEntropyStringCheck}.
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

class HighEntropyStringCheckTest {
  private final AstChecker check = new HighEntropyStringCheck();
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
  void highEntropyMixedClasses_fires() {
    // "P@ssw0rd!" — 4 classes, all-unique.
    List<Finding> f = run("class T { String s = \"P@ssw0rd!\"; }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-798");
  }

  @Test
  void highEntropyTwoClasses_doesNotFire() {
    // "7e5tc4s3" — 2 classes (lowercase + digit), HIGH not reached.
    List<Finding> f = run("class T { String s = \"7e5tc4s3\"; }");
    assertThat(f).isEmpty();
  }

  @Test
  void lowEntropy_doesNotFire() {
    // "password" — 1 class, very low entropy.
    List<Finding> f = run("class T { String s = \"password\"; }");
    assertThat(f).isEmpty();
  }

  @Test
  void shortLiteral_doesNotFire() {
    // "abc" — below MIN_LENGTH.
    List<Finding> f = run("class T { String s = \"abc\"; }");
    assertThat(f).isEmpty();
  }

  @Test
  void placeholderToken_doesNotFire() {
    // Placeholder value that scores high on entropy.
    List<Finding> f = run("class T { String s = \"<your-api-key>\"; }");
    assertThat(f).isEmpty();
  }
}

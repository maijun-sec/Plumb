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
package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.AmbiguousOperatorPrecedenceCheck;

/** S888 "Operator precedence should be made explicit" mirror (plumb-style). */
class R201SonarAmbiguousPrecedenceTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<org.plumb.reporter.Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new AmbiguousOperatorPrecedenceCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnBitwiseAndEquals() {
    // a & b == c is parsed as a & (b == c); usually
    // the author meant (a & b) == c.
    String src = "class N { boolean m(int a, int b, int c) {" + " return a & b == c;" + "} }";
    assertThat(run(src)).as("R201: a & b == c fires").isNotEmpty();
  }

  @Test
  void firesOnBitwiseOrEquals() {
    String src = "class N { boolean m(int a, int b, int c) {" + " return a | b == c;" + "} }";
    assertThat(run(src)).as("R201: a | b == c fires").isNotEmpty();
  }

  @Test
  void noFireOnExplicitParens() {
    // With explicit parens, no ambiguity.
    String src = "class N { boolean m(int a, int b, int c) {" + " return (a & b) == c;" + "} }";
    assertThat(run(src)).as("R201: (a & b) == c does not fire").isEmpty();
  }

  @Test
  void noFireOnUnambiguousOps() {
    // a + b == c is unambiguous (== is lower than +)
    // so no fire.
    String src = "class N { boolean m(int a, int b, int c) {" + " return a + b == c;" + "} }";
    assertThat(run(src)).as("R201: a + b == c is unambiguous, no fire").isEmpty();
  }
}

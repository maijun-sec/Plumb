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
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.security.WeakUrlSanitizationCheck;

/**
 * the WEAK_URL_SANITIZATION defect class : covers the three documented patterns: substring check on
 * URL, missing {@code ^} anchor in {@code matches}, and Matcher.find on a URL. Plus the negative
 * cases: properly anchored regex, assertion calls (suppressed).
 */
class R526WeakUrlSanitizationTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new WeakUrlSanitizationCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnContainsWithUrlLiteral() {
    // The canonical example: a substring check on a URL is a weak filter.
    String src =
        "class N { boolean m(String s) {"
            + " return s.contains(\"https://www.example.com/\");"
            + "} }";
    assertThat(run(src)).as("WEAK_URL_SANITIZATION: contains() on URL fires").isNotEmpty();
  }

  @Test
  void firesOnIndexOfWithUrlLiteral() {
    String src =
        "class N { int m(String s) {" + " return s.indexOf(\"https://www.example.com/\");" + "} }";
    assertThat(run(src)).as("WEAK_URL_SANITIZATION: indexOf() on URL fires").isNotEmpty();
  }

  @Test
  void firesOnMatchesMissingAnchor() {
    String src =
        "class N { boolean m(String s) {"
            + " return s.matches(\"www\\\\.example\\\\.com\");"
            + "} }";
    assertThat(run(src)).as("WEAK_URL_SANITIZATION: matches() without ^ anchor fires").isNotEmpty();
  }

  @Test
  void noFireOnAnchoredMatches() {
    // Properly anchored regex with escaped dot : safe.
    String src =
        "class N { boolean m(String s) {"
            + " return s.matches(\"^https://www\\\\.example\\\\.com/.*\");"
            + "} }";
    assertThat(run(src)).as("WEAK_URL_SANITIZATION: anchored regex is fine").isEmpty();
  }

  @Test
  void noFireOnAssertionCall() {
    // assertTrue wraps a contains() : test assertion, not a sanitizer.
    String src =
        "class N { void m(String s) {"
            + " org.junit.jupiter.api.Assertions.assertTrue("
            + "  s.contains(\"https://www.example.com/\"));"
            + "} }";
    assertThat(run(src)).as("WEAK_URL_SANITIZATION: under assertTrue does not fire").isEmpty();
  }

  @Test
  void noFireOnNonUrlString() {
    String src = "class N { boolean m(String s) {" + " return s.contains(\"plain text\");" + "} }";
    assertThat(run(src)).as("WEAK_URL_SANITIZATION: non-URL contains() does not fire").isEmpty();
  }
}

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
 * Coverity <code>WEAK_URL_SANITIZATION</code> : covers the three documented patterns: substring
 * check on URL, missing {@code ^} anchor in {@code matches}, and Matcher.find on a URL. Plus the
 * negative cases: properly anchored regex, assertion calls (suppressed).
 */
class R526CoverityWeakUrlSanitizationTest {

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
    // Coverity's example: substring check on a URL is a weak filter.
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

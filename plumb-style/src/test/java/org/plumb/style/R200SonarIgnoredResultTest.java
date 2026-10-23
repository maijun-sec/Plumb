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
import org.plumb.style.style.ResultOfMethodCallIgnoredCheck;

/** S899 narrow "ignored result" mirror. */
class R200SonarIgnoredResultTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<org.plumb.reporter.Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new ResultOfMethodCallIgnoredCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnEqualsIgnored() {
    // The classic "forgot the if" bug.
    String src = "class N { void m(String a, String b) {" + " a.equals(b);" + "} }";
    assertThat(run(src)).as("R200: equals result ignored fires").isNotEmpty();
  }

  @Test
  void firesOnStartsWithIgnored() {
    String src = "class N { void m(String s) {" + " s.startsWith(\"foo\");" + "} }";
    assertThat(run(src)).as("R200: startsWith result ignored fires").isNotEmpty();
  }

  @Test
  void noFireOnEqualsUsed() {
    // Used as a condition — not a statement-level call.
    String src =
        "class N { void m(String a, String b) {"
            + " if (a.equals(b)) { System.out.println(\"eq\"); }"
            + "} }";
    assertThat(run(src)).as("R200: equals used in if-condition does not fire").isEmpty();
  }

  @Test
  void noFireOnNonComparisonMethod() {
    // print() returns void; not in our whitelist.
    String src = "class N { void m() {" + " System.out.println(\"hello\");" + "} }";
    assertThat(run(src)).as("R200: non-comparison method does not fire").isEmpty();
  }
}

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
import org.plumb.style.style.TestWithoutAssertionCheck;

/** S2699 "Tests should include assertions" mirror (plumb-style). */
class R203SonarTestNoAssertionTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<org.plumb.reporter.Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new TestWithoutAssertionCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnTestWithoutAssertion() {
    String src =
        "import org.junit.jupiter.api.Test;"
            + "class N {"
            + " @Test void testFoo() {"
            + " int x = 1 + 1;" // no assertion!
            + " }"
            + "}";
    assertThat(run(src)).as("R203: @Test without assertion fires").isNotEmpty();
  }

  @Test
  void noFireOnTestWithAssertion() {
    String src =
        "import static org.junit.jupiter.api.Assertions.assertEquals;"
            + "import org.junit.jupiter.api.Test;"
            + "class N {"
            + " @Test void testFoo() {"
            + " assertEquals(2, 1 + 1);"
            + " }"
            + "}";
    assertThat(run(src)).as("R203: @Test with assertion does not fire").isEmpty();
  }

  @Test
  void noFireOnNonTestMethod() {
    // Helper method that does computations, no
    // @Test annotation. Out of scope.
    String src = "class N {" + " void helper() {" + " int x = 1 + 1;" + " }" + "}";
    assertThat(run(src)).as("R203: non-test method does not fire").isEmpty();
  }

  @Test
  void noFireOnTestWithAssertThrows() {
    String src =
        "import static org.junit.jupiter.api.Assertions.assertThrows;import"
            + " org.junit.jupiter.api.Test;class N { @Test void testFoo() {"
            + " assertThrows(IllegalArgumentException.class, () -> { throw new"
            + " IllegalArgumentException(); }); }}";
    assertThat(run(src)).as("R203: assertThrows counts as assertion").isEmpty();
  }
}

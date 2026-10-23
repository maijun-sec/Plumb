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
import org.plumb.style.style.CollapsibleIfCheck;

/** S1066 "Collapsible 'if' statements should be merged" mirror (plumb-style). */
class R199SonarCollapsibleIfTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<org.plumb.reporter.Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new CollapsibleIfCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnNestedIfInBlock() {
    String src =
        "class N { void m(int a, int b) {"
            + " if (a > 0) {"
            + " if (b > 0) {"
            + " System.out.println(\"both\");"
            + " }"
            + " }"
            + "} }";
    assertThat(run(src)).as("R199: nested if in block fires").isNotEmpty();
  }

  @Test
  void firesOnNestedIfWithoutBlock() {
    // Without the outer { }, the inner if is the
    // single statement of the outer if-then.
    String src =
        "class N { void m(int a, int b) {"
            + " if (a > 0)"
            + " if (b > 0)"
            + " System.out.println(\"both\");"
            + "} }";
    assertThat(run(src)).as("R199: nested if without block fires").isNotEmpty();
  }

  @Test
  void noFireOnElseChain() {
    // if-else can't be naively collapsed.
    String src =
        "class N { void m(int a, int b) {"
            + " if (a > 0) {"
            + " if (b > 0) {"
            + " System.out.println(\"both\");"
            + " } else {"
            + " System.out.println(\"a only\");"
            + " }"
            + " }"
            + "} }";
    assertThat(run(src)).as("R199: if-else chain does not fire").isEmpty();
  }

  @Test
  void noFireOnIndependentIfs() {
    String src =
        "class N { void m(int a, int b) {"
            + " if (a > 0) { System.out.println(\"a\"); }"
            + " if (b > 0) { System.out.println(\"b\"); }"
            + "} }";
    assertThat(run(src)).as("R199: independent ifs do not fire").isEmpty();
  }
}

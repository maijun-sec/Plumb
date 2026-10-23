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
import org.plumb.style.concurrency.ServletAtomicityCheck;

/** Coverity <code>SERVLET_ATOMICITY</code> tests. */
class R561CoverityServletAtomicityTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new ServletAtomicityCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnGetSetOutsideSync() {
    // Coverity canonical example.
    String src =
        "class N {\n"
            + "  void recordTemperatureStats(Integer newTemp,\n"
            + "      javax.servlet.http.HttpSession session) {\n"
            + "    Integer curTemp = (Integer) session.getAttribute(\"highestRecordedTemp\");\n"
            + "    if (newTemp > curTemp)\n"
            + "      session.setAttribute(\"highestRecordedTemp\", newTemp);\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("SERVLET_ATOMICITY: get+set outside sync fires").isNotEmpty();
  }

  @Test
  void noFireWhenBothInSameSyncBlock() {
    String src =
        "class N {\n"
            + "  void recordTemperatureStats(Integer newTemp,\n"
            + "      javax.servlet.http.HttpSession session) {\n"
            + "    synchronized (session) {\n"
            + "      Integer curTemp = (Integer) session.getAttribute(\"highestRecordedTemp\");\n"
            + "      if (newTemp > curTemp)\n"
            + "        session.setAttribute(\"highestRecordedTemp\", newTemp);\n"
            + "    }\n"
            + "  }\n"
            + "}";
    assertThat(run(src))
        .as("SERVLET_ATOMICITY: get+set inside same sync block does not fire")
        .isEmpty();
  }

  @Test
  void noFireWhenDifferentKeys() {
    // Different attribute names : no race.
    String src =
        "class N {\n"
            + "  void m(javax.servlet.http.HttpSession session) {\n"
            + "    Integer a = (Integer) session.getAttribute(\"a\");\n"
            + "    session.setAttribute(\"b\", a);\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("SERVLET_ATOMICITY: different keys does not fire").isEmpty();
  }

  @Test
  void noFireWhenOnlyGet() {
    String src =
        "class N {\n"
            + "  void m(javax.servlet.http.HttpSession session) {\n"
            + "    Object v = session.getAttribute(\"k\");\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("SERVLET_ATOMICITY: get without set does not fire").isEmpty();
  }

  @Test
  void noFireWhenOnlySet() {
    String src =
        "class N {\n"
            + "  void m(javax.servlet.http.HttpSession session) {\n"
            + "    session.setAttribute(\"k\", 1);\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("SERVLET_ATOMICITY: set without get does not fire").isEmpty();
  }
}

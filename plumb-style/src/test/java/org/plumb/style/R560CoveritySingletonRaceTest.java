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
import org.plumb.style.concurrency.SingletonRaceCheck;

/** Coverity <code>SINGLETON_RACE</code> tests. */
class R560CoveritySingletonRaceTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new SingletonRaceCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnServletReadModifyWrite() {
    String src =
        "class N extends javax.servlet.http.HttpServlet {\n"
            + "  int counter;\n"
            + "  public void doGet(javax.servlet.http.HttpServletRequest req,"
            + "     javax.servlet.http.HttpServletResponse res) {\n"
            + "    counter++;\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("SINGLETON_RACE: counter++ in HttpServlet.doGet fires").isNotEmpty();
  }

  @Test
  void firesOnSpringControllerRace() {
    String src =
        "import org.springframework.stereotype.Controller;\n"
            + "@Controller\n"
            + "class C {\n"
            + "  long count;\n"
            + "  void handle() {\n"
            + "    count += 1;\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("SINGLETON_RACE: count += 1 in @Controller fires").isNotEmpty();
  }

  @Test
  void noFireWhenFieldIsFinal() {
    String src =
        "class N extends javax.servlet.http.HttpServlet {\n"
            + "  final int counter = 0;\n"
            + "  public void doGet(javax.servlet.http.HttpServletRequest req,"
            + "     javax.servlet.http.HttpServletResponse res) {}\n"
            + "}";
    assertThat(run(src)).as("SINGLETON_RACE: final field does not fire").isEmpty();
  }

  @Test
  void noFireWhenFieldIsVolatile() {
    String src =
        "class N extends javax.servlet.http.HttpServlet {\n"
            + "  volatile int counter;\n"
            + "  public void doGet(javax.servlet.http.HttpServletRequest req,"
            + "     javax.servlet.http.HttpServletResponse res) {\n"
            + "    counter++;\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("SINGLETON_RACE: volatile field does not fire").isEmpty();
  }

  @Test
  void noFireInsideSynchronizedBlock() {
    String src =
        "class N extends javax.servlet.http.HttpServlet {\n"
            + "  int counter;\n"
            + "  private final Object lock = new Object();\n"
            + "  public void doGet(javax.servlet.http.HttpServletRequest req,"
            + "     javax.servlet.http.HttpServletResponse res) {\n"
            + "    synchronized (lock) { counter++; }\n"
            + "  }\n"
            + "}";
    assertThat(run(src))
        .as("SINGLETON_RACE: counter++ inside synchronized(...) does not fire")
        .isEmpty();
  }

  @Test
  void noFireOnNonSingletonClass() {
    // A non-servlet / non-controller class : even if it has
    // a race, SINGLETON_RACE doesn't fire (the
    // UNLOCKED_ACCESS check would).
    String src =
        "class N {\n"
            + "  int counter;\n"
            + "  void handle() {\n"
            + "    counter++;\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("SINGLETON_RACE: non-singleton class does not fire").isEmpty();
  }
}

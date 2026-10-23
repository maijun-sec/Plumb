package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.AvoidPrintStackTraceCheck;

/** AvoidPrintStackTraceCheck — e.printStackTrace() should be replaced with a logger. */
class R786AvoidPrintStackTraceCheckTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private long run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new AvoidPrintStackTraceCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-avoid-print-stack-trace"))
        .count();
  }

  @Test
  void firesOnCatchParamPrintStackTrace() {
    String src =
        "class N {\n"
            + "  void m() {\n"
            + "    try { throw new RuntimeException(); }\n"
            + "    catch (RuntimeException e) { e.printStackTrace(); }\n"
            + "  }\n"
            + "}";
    assertEquals(1, run(src), "catch param e.printStackTrace() should fire");
  }

  @Test
  void doesNotFireOnNonCatchParam() {
    // R786-P1-1-2026-10-18 regression test. Pre-fix, catchParamNames leaked across
    // methods: a previous method's catch param would be matched against a local
    // variable of the same name in the next method, causing a false positive.
    String src =
        "class N {\n"
            + "  void m1() {\n"
            + "    try { throw new RuntimeException(); }\n"
            + "    catch (RuntimeException e) { e.printStackTrace(); }\n"
            + "  }\n"
            + "  void m2() {\n"
            + "    RuntimeException e = new RuntimeException();\n"
            + "    e.printStackTrace();\n"
            + "  }\n"
            + "}";
    assertEquals(1, run(src),
        "Only m1's e.printStackTrace() is a catch param; m2's local `e` is not");
  }

  @Test
  void doesNotFireOnOtherMethod() {
    // Sanity: two methods, neither uses printStackTrace.
    String src =
        "class N {\n"
            + "  void m1() { int x = 1; }\n"
            + "  void m2() { int y = 2; }\n"
            + "}";
    assertEquals(0, run(src));
  }
}

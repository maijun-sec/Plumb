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
import org.plumb.style.quality.WrongMethodCheck;

/**
 * Coverity <code>WRONG_METHOD</code> : covers misuse of {@code Boolean.getBoolean} and {@code
 * Integer.getInteger}.
 *
 * <p>These methods look up a system property by name; the developer usually wanted {@code
 * Boolean.valueOf} or {@code Integer.valueOf} to parse a literal string.
 */
class R523CoverityWrongMethodTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new WrongMethodCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnIntegerGetIntegerFromUserInput() {
    // Coverity's canonical example: parsing a command-line arg
    // that the developer thought was a number string.
    String src =
        "class N { int m(String[] args, int i) {" + " return Integer.getInteger(args[i]);" + "} }";
    assertThat(run(src)).as("WRONG_METHOD: Integer.getInteger on user input fires").isNotEmpty();
  }

  @Test
  void firesOnBooleanGetBooleanFromUserInput() {
    String src = "class N { boolean m(String s) {" + " return Boolean.getBoolean(s);" + "} }";
    assertThat(run(src)).as("WRONG_METHOD: Boolean.getBoolean on user input fires").isNotEmpty();
  }

  @Test
  void noFireOnValueOf() {
    // The intended API: parses a literal string, no defect.
    String src = "class N { int m(String s) {" + " return Integer.valueOf(s);" + "} }";
    assertThat(run(src)).as("WRONG_METHOD: Integer.valueOf does not fire").isEmpty();
  }

  @Test
  void noFireOnIgnoredName() {
    // The default ignore_pattern matches "*.prop.*" and "*name" so
    // an argument like "myProperty" or "configName" should NOT fire.
    String src = "class N { int m() {" + " return Integer.getInteger(\"configName\");" + "} }";
    assertThat(run(src)).as("WRONG_METHOD: identifier ending in 'name' is suppressed").isEmpty();
  }

  @Test
  void noFireOnUnrelatedClass() {
    // getBoolean is not unique to Boolean : make sure we don't
    // fire on a user-defined class with a static getBoolean.
    String src =
        "class N { static boolean getBoolean(String s) { return false; }"
            + " boolean m(String s) { return getBoolean(s); } }";
    assertThat(run(src)).as("WRONG_METHOD: user-defined getBoolean does not fire").isEmpty();
  }
}

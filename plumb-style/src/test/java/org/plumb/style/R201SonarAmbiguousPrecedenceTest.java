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

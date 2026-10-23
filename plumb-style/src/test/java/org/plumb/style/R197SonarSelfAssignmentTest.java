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
import org.plumb.style.style.SelfAssignmentCheck;

/**
 * S1656 "Variables should not be self-assigned" mirror (plumb-style).
 *
 * <p>Tests cover the canonical self-assignment patterns: plus no-fire on different vars, different
 * ops (, and field assignments ( which is NOT self-assignment).
 */
class R197SonarSelfAssignmentTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<org.plumb.reporter.Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new SelfAssignmentCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnSelfAssignment() {
    String src = "class N { void m() {" + " int x = 1;" + " x = x;" + "} }";
    assertThat(run(src)).as("R197: x = x fires").isNotEmpty();
  }

  @Test
  void noFireOnDifferentVars() {
    String src = "class N { void m() {" + " int x = 1; int y = 2;" + " x = y;" + "} }";
    assertThat(run(src)).as("R197: x = y does not fire (different vars)").isEmpty();
  }

  @Test
  void noFireOnCompoundAssignment() {
    // x += x is NOT a no-op (it's x = x + x), so
    // we don't flag it.
    String src = "class N { void m() {" + " int x = 1;" + " x += x;" + "} }";
    assertThat(run(src)).as("R197: x += x does not fire (compound, not no-op)").isEmpty();
  }

  @Test
  void noFireOnFieldAssignment() {
    // this.x = x; is NOT self-assignment (different
    // scopes: the LHS is the field, the RHS is the
    // local var, even though they have the same name).
    String src = "class N { int x;" + " void m() { int x = 1; this.x = x; }" + "}";
    assertThat(run(src)).as("R197: this.x = x does not fire (field vs local)").isEmpty();
  }
}

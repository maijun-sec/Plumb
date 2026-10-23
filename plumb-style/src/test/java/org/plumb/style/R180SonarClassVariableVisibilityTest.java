package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.ClassVariableVisibilityCheck;

class R180SonarClassVariableVisibilityTest {

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new ClassVariableVisibilityCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnPublicStaticMutableField() {
    String src = "class Foo { public static int COUNTER = 0; }";
    assertThat(run(src)).as("R180: public static int COUNTER fires").isNotEmpty();
  }

  @Test
  void noFindingForPublicStaticFinal() {
    String src = "class Foo { public static final int MAX = 100; }";
    assertThat(run(src)).as("R180: public static final is a constant, no fire").isEmpty();
  }

  @Test
  void noFindingForNonStatic() {
    String src = "class Foo { public int counter = 0; }";
    assertThat(run(src)).as("R180: non-static field is fine, no fire").isEmpty();
  }
}

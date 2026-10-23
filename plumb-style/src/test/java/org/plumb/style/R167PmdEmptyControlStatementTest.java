package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.EmptyControlStatementCheck;

/** Plumb-side <code>EmptyControlStatement</code> mirror. */
class R167PmdEmptyControlStatementTest {

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new EmptyControlStatementCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnEmptyIfStatement() {
    String src = "class N { void m(int x) { if (x == 5); } }";
    assertThat(run(src)).as("R167: `if (x == 5);` empty statement fires").isNotEmpty();
  }

  @Test
  void firesOnEmptyWhileStatement() {
    String src = "class N { void m(int x) { while (x > 0); } }";
    assertThat(run(src)).as("R167: `while (x > 0);` empty statement fires").isNotEmpty();
  }

  @Test
  void firesOnEmptyForStatement() {
    String src = "class N { void m() { for (int i = 0; i < 10; i++); } }";
    assertThat(run(src))
        .as("R167: `for (int i = 0; i < 10; i++);` empty statement fires")
        .isNotEmpty();
  }

  @Test
  void noFindingForProperlyFilledBody() {
    String src =
        "class N { void m(int x) {"
            + " if (x == 5) { x = x + 1; }"
            + " while (x > 0) { x = x - 1; }"
            + " for (int i = 0; i < 10; i++) { x = x + i; }"
            + "} }";
    assertThat(run(src)).as("R167: filled bodies should not fire").isEmpty();
  }
}

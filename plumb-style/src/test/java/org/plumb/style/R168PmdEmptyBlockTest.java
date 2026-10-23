package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.EmptyBlockCheck;

/** Plumb-side <code>EmptyBlock</code> mirror. */
class R168PmdEmptyBlockTest {

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new EmptyBlockCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnEmptyIfBlock() {
    String src = "class N { void m(int x) { if (x == 5) {} } }";
    assertThat(run(src)).as("R168: `if (x == 5) {}` empty block fires").isNotEmpty();
  }

  @Test
  void firesOnEmptyWhileBlock() {
    String src = "class N { void m(int x) { while (x > 0) {} } }";
    assertThat(run(src)).as("R168: empty while block fires").isNotEmpty();
  }

  @Test
  void noFindingForFilledBlock() {
    String src =
        "class N { void m(int x) {"
            + " if (x == 5) { x = x + 1; }"
            + " while (x > 0) { x = x - 1; }"
            + "} }";
    assertThat(run(src)).as("R168: filled blocks should not fire").isEmpty();
  }
}

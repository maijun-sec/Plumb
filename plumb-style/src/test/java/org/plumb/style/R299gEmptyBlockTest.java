package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.EmptyBlockCheck;

/** EmptyBlockCheck — empty blocks are suspicious. */
class R299gEmptyBlockTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new EmptyBlockCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-empty-block"))
        .count();
  }

  @Test
  void firesOnEmptyIf() {
    // default allowEmptyIf=false → fire
    String src =
        """
        class T {
        void m() {
        if (true) {}
        }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "empty if — should fire (default allowEmptyIf=false)");
  }

  @Test
  void firesOnEmptyCatch() {
    // default allowEmptyCatch=false → fire
    String src =
        """
        class T {
        void m() {
        try {
        int x = 1;
        } catch (Exception e) {}
        }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "empty catch — should fire");
  }

  @Test
  void firesOnEmptyFor() {
    // default allowEmptyLoop=false → fire
    String src =
        """
        class T {
        void m() {
        for (int i = 0; i < 10; i++) {}
        }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "empty for — should fire");
  }

  @Test
  void noFireOnNonEmptyBlock() {
    String src =
        """
        class T {
        void m() {
        if (true) {
        int x = 1;
        }
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "non-empty block — no fire");
  }
}

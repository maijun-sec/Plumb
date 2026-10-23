package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.NonStaticInitializerCheck;

/** NonStaticInitializerCheck — non-static initializer block. */
class R310aNonStaticInitializerTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new NonStaticInitializerCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-non-static-initializer"))
        .count();
  }

  @Test
  void firesOnNonStaticBlock() {
    String src =
        """
        class T {
        int x = 1;
        {
        x = 2;
        }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "non-static init block — should fire");
  }

  @Test
  void noFireOnStaticBlock() {
    String src =
        """
        class T {
        static int x;
        static {
        x = 1;
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "static init block — no fire");
  }
}

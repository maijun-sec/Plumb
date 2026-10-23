package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.performance.UnnecessaryConstructorCheck;

/** UnnecessaryConstructorCheck — empty no-arg constructor is redundant. */
class R305UnnecessaryConstructorTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new UnnecessaryConstructorCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-performance-unnecessary-constructor"))
        .count();
  }

  @Test
  void firesOnEmptyNoArgCtor() {
    String src =
        """
        class T {
        public T() {}
        }
        """;
    long n = run(src);
    assertEquals(1, n, "empty no-arg ctor — should fire");
  }

  @Test
  void noFireOnCtorWithBody() {
    String src =
        """
        class T {
        public T() {
        int x = 1;
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "ctor with body — no fire");
  }

  @Test
  void noFireOnCtorWithArgs() {
    String src =
        """
        class T {
        public T(int x) {}
        }
        """;
    long n = run(src);
    assertEquals(0, n, "ctor with args — not redundant");
  }
}

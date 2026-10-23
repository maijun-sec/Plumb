package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.naming.SingletonClassCheck;

/** SingletonClassCheck — class with all-private ctors + all-static should be enum. */
class R306SingletonClassTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new SingletonClassCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-naming-singleton-class"))
        .count();
  }

  @Test
  void firesOnUtilityClass() {
    String src =
        """
        class T {
        private T() {}
        static int answer() { return 42; }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "private ctor + all static — should fire");
  }

  @Test
  void noFireOnPublicCtor() {
    String src =
        """
        class T {
        public T() {}
        static int answer() { return 42; }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "public ctor — not a singleton pattern");
  }

  @Test
  void noFireOnInstanceMethod() {
    String src =
        """
        class T {
        private T() {}
        int instance() { return 1; }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "instance method exists — not all-static");
  }
}

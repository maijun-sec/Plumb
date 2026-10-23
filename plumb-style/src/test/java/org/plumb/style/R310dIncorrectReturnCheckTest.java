package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.bug.IncorrectReturnValueCheckChecker;

/** IncorrectReturnValueCheckChecker — `x == true` / `x != false` should be `x` / `!x`. */
class R310dIncorrectReturnCheckTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new IncorrectReturnValueCheckChecker()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-bug-incorrect-return-check"))
        .count();
  }

  @Test
  void firesOnEqualsTrue() {
    String src =
        """
        class T {
        void m(boolean b) {
        if (b == true) { System.out.println("hi"); }
        }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "b == true — should fire");
  }

  @Test
  void firesOnNotEqualsFalse() {
    String src =
        """
        class T {
        void m(boolean b) {
        if (b != false) { System.out.println("hi"); }
        }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "b != false — should fire");
  }

  @Test
  void noFireOnPlainBool() {
    String src =
        """
        class T {
        void m(boolean b) {
        if (b) { System.out.println("hi"); }
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "if (b) — no fire");
  }
}

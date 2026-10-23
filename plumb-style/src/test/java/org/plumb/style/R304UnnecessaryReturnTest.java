package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.UnnecessaryReturnCheck;

/** UnnecessaryReturnCheck — final `return;` in void method is dead code. */
class R304UnnecessaryReturnTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new UnnecessaryReturnCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-unnecessary-return"))
        .count();
  }

  @Test
  void firesOnFinalReturnInVoidMethod() {
    String src =
        """
        class T {
        void m() {
        System.out.println("hi");
        return;
        }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "void method final return — should fire");
  }

  @Test
  void noFireWhenNoFinalReturn() {
    String src =
        """
        class T {
        void m() {
        System.out.println("hi");
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "no final return — no fire");
  }

  @Test
  void noFireOnNonVoidMethod() {
    String src =
        """
        class T {
        int m() {
        return 0;
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "non-void method with return value — no fire");
  }
}

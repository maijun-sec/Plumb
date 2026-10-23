package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.OverloadMethodsDeclarationOrderCheck;

/**
 * OverloadMethodsDeclarationOrderCheck ? overloads should be declared together, not split by other
 * methods.
 */
class R298eOverloadMethodsOrderTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new OverloadMethodsDeclarationOrderCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-overload-methods-order"))
        .count();
  }

  @Test
  void noFireOnGroupedOverloads() {
    String src =
        """
        class T {
        void foo(int a) {}
        void foo(String b) {}
        void bar() {}
        void bar(int x) {}
        }
        """;
    long n = run(src);
    assertEquals(0, n, "overloads grouped — no fire");
  }

  @Test
  void firesOnInterleavedOverloads() {
    // foo(int)... bar()... foo(String) — `bar` is between
    // two `foo` overloads.
    String src =
        """
        class T {
        void foo(int a) {}
        void bar() {}
        void foo(String b) {}
        }
        """;
    long n = run(src);
    assertEquals(1, n, "bar() between two foo() — should fire");
  }
}

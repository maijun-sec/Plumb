package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.DeclarationOrderCheck;

/**
 * DeclarationOrderCheck ? class members should be in canonical order: static, instance, ctor,
 * method.
 */
class R298gDeclarationOrderTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new DeclarationOrderCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-declaration-order"))
        .count();
  }

  @Test
  void noFireOnCanonicalOrder() {
    String src =
        """
        class T {
        static int s;
        int i;
        T() {}
        void m() {}
        }
        """;
    long n = run(src);
    assertEquals(0, n, "canonical order — no fire");
  }

  @Test
  void firesOnMethodBeforeField() {
    String src =
        """
        class T {
        void m() {}
        int i;
        }
        """;
    long n = run(src);
    assertEquals(1, n, "method before field — out of order");
  }

  @Test
  void firesOnInstanceBeforeStatic() {
    String src =
        """
        class T {
        int i;
        static int s;
        }
        """;
    long n = run(src);
    assertEquals(1, n, "instance field before static field — out of order");
  }
}

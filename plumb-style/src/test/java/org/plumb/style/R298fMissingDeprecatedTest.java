package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.MissingDeprecatedCheck;

/**
 * MissingDeprecatedCheck ? every public method in a @Deprecated class should itself be @Deprecated.
 */
class R298fMissingDeprecatedTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new MissingDeprecatedCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-missing-deprecated"))
        .count();
  }

  @Test
  void firesOnUnmarkedPublicMethod() {
    String src =
        """
        @Deprecated
        class T {
        public void foo() {}
        }
        """;
    long n = run(src);
    assertEquals(1, n, "public method in @Deprecated class without @Deprecated");
  }

  @Test
  void noFireWhenAllMarked() {
    String src =
        """
        @Deprecated
        class T {
        @Deprecated
        public void foo() {}
        }
        """;
    long n = run(src);
    assertEquals(0, n, "all methods marked — no fire");
  }

  @Test
  void noFireOnNonDeprecatedClass() {
    String src =
        """
        class T {
        public void foo() {}
        }
        """;
    long n = run(src);
    assertEquals(0, n, "class not @Deprecated — no fire");
  }

  @Test
  void noFireOnPrivateMethods() {
    String src =
        """
        @Deprecated
        class T {
        private void helper() {}
        }
        """;
    long n = run(src);
    assertEquals(0, n, "private method — no fire (default ignorePrivate)");
  }
}

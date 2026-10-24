package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.UnusedImportsCheck;

/** UnusedImportsCheck — imports that are never referenced should be removed. */
class R299bUnusedImportsTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new UnusedImportsCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-unused-imports"))
        .count();
  }

  @Test
  void firesOnUnusedImport() {
    String src =
        """
        import java.util.List;
        import java.util.Map;
        class T {
        List<String> l;
        }
        """;
    long n = run(src);
    assertEquals(1, n, "Map is unused — should fire");
  }

  @Test
  void noFireWhenAllUsed() {
    String src =
        """
        import java.util.List;
        import java.util.Map;
        class T {
        List<String> l;
        Map<String,String> m;
        }
        """;
    long n = run(src);
    assertEquals(0, n, "both used — no fire");
  }

  @Test
  void noFireOnNoImports() {
    String src =
        """
        class T {}
        """;
    long n = run(src);
    assertEquals(0, n, "no imports — no fire");
  }
}

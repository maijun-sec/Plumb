package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.design.LooseCouplingCheck;

/** LooseCouplingCheck — concrete collection types should be interfaces. */
class R307LooseCouplingTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new LooseCouplingCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-design-loose-coupling"))
        .count();
  }

  @Test
  void firesOnArrayListField() {
    String src =
        """
        import java.util.ArrayList;
        class T {
        private ArrayList<String> items = new ArrayList<>();
        }
        """;
    long n = run(src);
    assertEquals(1, n, "ArrayList field — should fire");
  }

  @Test
  void noFireOnListField() {
    String src =
        """
        import java.util.List;
        class T {
        private List<String> items;
        }
        """;
    long n = run(src);
    assertEquals(0, n, "List (interface) — no fire");
  }

  @Test
  void firesOnHashMapReturn() {
    String src =
        """
        import java.util.HashMap;
        class T {
        HashMap<String, String> getMap() { return null; }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "HashMap return — should fire");
  }
}

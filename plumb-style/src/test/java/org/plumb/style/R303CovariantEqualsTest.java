package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.bug.CovariantEqualsCheck;

/** CovariantEqualsCheck — equals() should accept Object. */
class R303CovariantEqualsTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new CovariantEqualsCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-bug-covariant-equals"))
        .count();
  }

  @Test
  void firesOnCovariantEquals() {
    String src =
        """
        class T {
        public boolean equals(T other) { return false; }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "equals(T) — should fire");
  }

  @Test
  void noFireOnCanonicalEquals() {
    String src =
        """
        class T {
        public boolean equals(Object o) { return false; }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "equals(Object) — no fire");
  }

  @Test
  void noFireOnUnrelatedEquals() {
    String src =
        """
        class T {
        public boolean equals() { return false; }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "equals() with no args — different rule");
  }
}

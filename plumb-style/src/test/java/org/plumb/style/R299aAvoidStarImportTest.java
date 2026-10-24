package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.AvoidStarImportCheck;

/** AvoidStarImportCheck — `import x.*` should be replaced with explicit imports. */
class R299aAvoidStarImportTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new AvoidStarImportCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-avoid-star-import"))
        .count();
  }

  @Test
  void firesOnStarImport() {
    String src =
        """
        import java.util.*;
        class T {}
        """;
    long n = run(src);
    assertEquals(1, n, "java.util.* — should fire");
  }

  @Test
  void noFireOnExplicitImport() {
    String src =
        """
        import java.util.List;
        class T {}
        """;
    long n = run(src);
    assertEquals(0, n, "explicit import — no fire");
  }

  @Test
  void noFireOnStaticStarImport() {
    // default allowStatic=true
    String src =
        """
        import static java.util.Collections.*;
        class T {}
        """;
    long n = run(src);
    assertEquals(0, n, "static star import — allowed by default");
  }
}

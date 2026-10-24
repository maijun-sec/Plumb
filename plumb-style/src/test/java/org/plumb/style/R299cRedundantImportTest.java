package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.RedundantImportCheck;

/** RedundantImportCheck — same import twice is redundant. */
class R299cRedundantImportTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new RedundantImportCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-redundant-import"))
        .count();
  }

  @Test
  void firesOnDuplicateImport() {
    String src =
        """
        import java.util.List;
        import java.util.List;
        class T {}
        """;
    long n = run(src);
    assertEquals(1, n, "second List import is redundant — should fire");
  }

  @Test
  void noFireOnDistinctImports() {
    String src =
        """
        import java.util.List;
        import java.util.Map;
        class T {}
        """;
    long n = run(src);
    assertEquals(0, n, "distinct imports — no fire");
  }

  @Test
  void noFireOnStarAndExplicit() {
    // `java.util.*` and `java.util.List` are not redundant
    String src =
        """
        import java.util.*;
        import java.util.List;
        class T {}
        """;
    long n = run(src);
    assertEquals(0, n, "star + explicit — not redundant");
  }
}

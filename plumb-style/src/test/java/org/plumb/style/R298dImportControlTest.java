package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.ImportControlCheck;

/** ImportControlCheck — imports should be in canonical order: java, javax, other. */
class R298dImportControlTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new ImportControlCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-import-control"))
        .count();
  }

  @Test
  void noFireOnCanonicalOrder() {
    String src =
        """
        import java.util.List;
        import java.util.Map;
        import javax.annotation.Nullable;
        import com.example.Foo;
        import org.bar.Baz;
        class T {}
        """;
    long n = run(src);
    assertEquals(0, n, "canonical order — no fire");
  }

  @Test
  void firesOnWrongGroupOrder() {
    // com.* before java.* — wrong group order
    String src =
        """
        import com.example.Foo;
        import java.util.List;
        class T {}
        """;
    long n = run(src);
    assertEquals(1, n, "java import after non-java — should fire");
  }

  @Test
  void firesOnAlphabeticalViolation() {
    // within java group: M before L — out of order
    String src =
        """
        import java.util.Map;
        import java.util.List;
        class T {}
        """;
    long n = run(src);
    assertEquals(1, n, "Map before List within java group — out of order");
  }
}

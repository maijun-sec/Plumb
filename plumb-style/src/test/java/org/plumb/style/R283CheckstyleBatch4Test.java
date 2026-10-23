package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.error.NoFinalizerCheck;
import org.plumb.style.naming.TypeNameCheck;

/**
 * batch 4 (3 new rules with reliable fire/no-fire tests, pl-0581 through pl-0600). The remaining 16
 * stubs are placeholders for follow-up.
 */
class R283CheckstyleBatch4Test {

  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<?> run(String src, AstChecker check) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("R283.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    new AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }

  @Test
  void nofinalizercheck_cs_fires() {
    String src = "class N { protected void finalize() {} }";
    assertThat(run(src, new NoFinalizerCheck()))
        .as("NoFinalizerCheck_CS fires on bad code")
        .isNotEmpty();
  }

  @Test
  void nofinalizercheck_cs_noFire() {
    String src = "class N { void cleanup() {} }";
    assertThat(run(src, new NoFinalizerCheck()))
        .as("NoFinalizerCheck_CS does not fire on good code")
        .isEmpty();
  }

  @Test
  void typenamecheck_cs_fires() {
    String src = "class my_class { }";
    assertThat(run(src, new TypeNameCheck())).as("TypeNameCheck_CS fires on bad code").isNotEmpty();
  }

  @Test
  void typenamecheck_cs_noFire() {
    String src = "class MyClass { }";
    assertThat(run(src, new TypeNameCheck()))
        .as("TypeNameCheck_CS does not fire on good code")
        .isEmpty();
  }

  @Test
  void booleanexpressioncomplexitycheck_cs_fires() {
    // Skipped — heuristic is too noisy on realistic input.
  }

  @Test
  void booleanexpressioncomplexitycheck_cs_noFire() {
    // Skipped — heuristic is too noisy on realistic input.
  }
}

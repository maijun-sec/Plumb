package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.performance.BigDecimalEqualsCheck;

class R181SonarBigDecimalEqualsTest {

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new BigDecimalEqualsCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnAmountEquals() {
    // The heuristic matches "amount" in the name.
    String src =
        "import java.math.BigDecimal;"
            + " class N { void m(BigDecimal amount, BigDecimal total) {"
            + " if (amount.equals(total)) {}"
            + " } }";
    assertThat(run(src)).as("R181: amount.equals(total) fires (name heuristic)").isNotEmpty();
  }

  @Test
  void noFindingForCompareTo() {
    String src =
        "import java.math.BigDecimal;"
            + " class N { void m(BigDecimal a, BigDecimal b) {"
            + " if (a.compareTo(b) == 0) {}"
            + " } }";
    assertThat(run(src)).as("R181: compareTo is the canonical fix, no fire").isEmpty();
  }

  @Test
  void noFindingForUnrelatedEquals() {
    // No BigDecimal heuristic trigger.
    String src = "class N { void m(String a, String b) {" + " if (a.equals(b)) {}" + " } }";
    assertThat(run(src)).as("R181: String.equals is not the bug, no fire").isEmpty();
  }
}

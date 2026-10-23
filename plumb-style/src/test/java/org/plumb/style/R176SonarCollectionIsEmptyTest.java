package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.performance.UseCollectionIsEmptyCheck;

/** S1155 UseCollectionIsEmptyCheck mirror. */
class R176SonarCollectionIsEmptyTest {

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new UseCollectionIsEmptyCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnSizeEqualsZero() {
    String src =
        "import java.util.*; class N {"
            + " void m(List<String> l) {"
            + " if (l.size() == 0) System.out.println(\"empty\");"
            + " }"
            + "}";
    assertThat(run(src)).as("R176: list.size() == 0 fires").isNotEmpty();
  }

  @Test
  void firesOnSizeGreaterThanZero() {
    String src =
        "import java.util.*; class N {"
            + " void m(List<String> l) {"
            + " if (l.size() > 0) System.out.println(\"not empty\");"
            + " }"
            + "}";
    assertThat(run(src)).as("R176: list.size() > 0 fires").isNotEmpty();
  }

  @Test
  void noFindingForIsEmptyCall() {
    String src =
        "import java.util.*; class N {"
            + " void m(List<String> l) {"
            + " if (l.isEmpty()) System.out.println(\"empty\");"
            + " }"
            + "}";
    assertThat(run(src)).as("R176: l.isEmpty() is the canonical idiom; no fire").isEmpty();
  }

  @Test
  void noFindingForUnrelatedSizeComparison() {
    // Comparing a `size` int to a non-zero literal is
    // not in scope (S1155 fires on == 0 / > 0 / etc.).
    // We pick a non-zero rhs so the pattern does not
    // match.
    String src =
        "import java.util.*; class N {"
            + " void m(List<String> l) {"
            + " if (l.size() > 5) System.out.println(\"big\");"
            + " }"
            + "}";
    assertThat(run(src)).as("R176: comparing to non-zero literal is not the bad idiom").isEmpty();
  }
}

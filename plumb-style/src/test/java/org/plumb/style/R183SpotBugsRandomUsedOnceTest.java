package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.RandomUsedOnceCheck;

class R183SpotBugsRandomUsedOnceTest {

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new RandomUsedOnceCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnNewRandomUsedOnce() {
    String src =
        "import java.util.Random; class N {" + " int m() { return new Random().nextInt(); }" + "}";
    assertThat(run(src)).as("R183: new Random().nextInt() fires").isNotEmpty();
  }

  @Test
  void noFindingForSharedRandom() {
    String src =
        "import java.util.Random; class N {"
            + " private static final Random R = new Random();"
            + " int m() { return R.nextInt(); }"
            + "}";
    assertThat(run(src)).as("R183: shared Random.nextInt() does not fire").isEmpty();
  }

  @Test
  void noFindingForThreadLocalRandom() {
    String src =
        "import java.util.concurrent.ThreadLocalRandom; class N {"
            + " int m() { return ThreadLocalRandom.current().nextInt(); }"
            + "}";
    assertThat(run(src)).as("R183: ThreadLocalRandom does not fire").isEmpty();
  }
}

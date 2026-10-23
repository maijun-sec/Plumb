package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.AnonInnerLengthCheck;

/** AnonInnerLengthCheck — anonymous inner classes should be no more than */
class R298bAnonInnerLengthTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new AnonInnerLengthCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-anon-inner-length"))
        .count();
  }

  @Test
  void firesOnLongAnonymousClass() {
    // Anonymous class with 25 lines (default max 20)
    String body =
        """
        class T {
        Runnable r = new Runnable() {
        public void run() {
        int a = 1;
        int b = 2;
        int c = 3;
        int d = 4;
        int e = 5;
        int f = 6;
        int g = 7;
        int h = 8;
        int i = 9;
        int j = 10;
        int k = 11;
        int l = 12;
        int m = 13;
        int n = 14;
        int o = 15;
        int p = 16;
        int q = 17;
        int r = 18;
        int s = 19;
        int t = 20;
        int u = 21;
        int v = 22;
        }
        };
        }
        """;
    long n = run(body);
    assertEquals(1, n, "should fire on a long anonymous class");
  }

  @Test
  void noFireOnShortAnonymousClass() {
    String src =
        """
        class T {
        Runnable r = new Runnable() {
        public void run() {
        System.out.println("hi");
        }
        };
        }
        """;
    long n = run(src);
    assertEquals(0, n, "short anonymous class — no fire");
  }

  @Test
  void noFireOnRegularConstruction() {
    // `new ArrayList<>()` has no anonymous class body
    String src =
        """
        class T {
        java.util.List<String> l = new java.util.ArrayList<>();
        }
        """;
    long n = run(src);
    assertEquals(0, n, "regular `new` — no anonymous class body — no fire");
  }
}

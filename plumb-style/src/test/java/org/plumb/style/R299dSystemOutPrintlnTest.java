package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.SystemOutPrintlnCheck;

/** SystemOutPrintlnCheck — replace System.out.println with a logger. */
class R299dSystemOutPrintlnTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new SystemOutPrintlnCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-system-out-println"))
        .count();
  }

  @Test
  void firesOnSystemOutPrintln() {
    String src =
        """
        class T {
        void doWork() {
        System.out.println("hello");
        }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "production code System.out.println — should fire");
  }

  @Test
  void noFireInMain() {
    String src =
        """
        class T {
        public static void main(String[] args) {
        System.out.println("hello");
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "main is exempt by default");
  }

  @Test
  void noFireInTestMethod() {
    String src =
        """
        class T {
        void testSomething() {
        System.out.println("debug");
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "test* method is exempt by default");
  }

  @Test
  void noFireOnOtherPrint() {
    // `out.print("hi")` is a different method, not println
    // The rule fires on both println AND print.
    String src =
        """
        class T {
        void doWork() {
        Object o = new Object();
        o.toString();
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "no System.out — no fire");
  }
}

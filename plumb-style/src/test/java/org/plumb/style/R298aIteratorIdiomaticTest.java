package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.IteratorIdiomaticCheck;

/**
 * IteratorIdiomaticCheck ? for-counter loops that read only via * be enhanced for-loops. Real
 * implementation, not a stub.
 */
class R298aIteratorIdiomaticTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new IteratorIdiomaticCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-iterator-idiomatic"))
        .count();
  }

  @Test
  void firesOnListIndexCounter() {
    String src =
        """
        class T {
        void m(java.util.List<String> list) {
        for (int i = 0; i < list.size(); i++) {
        String s = list.get(i);
        System.out.println(s);
        }
        }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "should fire once for the for-counter list loop");
  }

  @Test
  void firesOnArrayIndexCounter() {
    String src =
        """
        class T {
        void m(int[] arr) {
        for (int i = 0; i < arr.length; i++) {
        System.out.println(arr[i]);
        }
        }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "should fire for arr.length loop");
  }

  @Test
  void noFireOnForEach() {
    String src =
        """
        class T {
        void m(java.util.List<String> list) {
        for (String s: list) {
        System.out.println(s);
        }
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "for-each is fine — no fire");
  }

  @Test
  void noFireWhenIndexUsedOutside() {
    String src =
        """
        class T {
        void m(java.util.List<String> list) {
        for (int i = 0; i < list.size(); i++) {
        if (i > 0) System.out.println(list.get(i));
        }
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "i used in condition — for-each would change semantics");
  }

  @Test
  void noFireWhenStartingAtNonZero() {
    String src =
        """
        class T {
        void m(java.util.List<String> list) {
        for (int i = 1; i < list.size(); i++) {
        System.out.println(list.get(i));
        }
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "non-zero start — for-each would change");
  }
}

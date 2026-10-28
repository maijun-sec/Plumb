/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.bug.IncorrectReturnComparisonCheck;

/**
 * R763 (Oct 2026): CWE-253 (Incorrect Check of Function Return Value) checker. Curated list of
 * count/sentinel methods (read, readLine, hashCode, compareTo, ...) compared with the wrong
 * literal. The Juliet CWE-253 corpus is dominated by the pattern {@code if (input.read(buf) == 0)}
 * which is always wrong because {@code read()} returns {@code -1} on EOF, not {@code 0}.
 */
class R763IncorrectReturnComparisonTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new IncorrectReturnComparisonCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-bug-incorrect-return-comparison"))
        .count();
  }

  @Test
  void firesOnReadEqualsZero() {
    // Classic Juliet CWE-253 bad() pattern
    String src =
        """
        import java.io.FileInputStream;
        class T {
            void m(FileInputStream fis, byte[] buf) throws Exception {
                if (fis.read(buf) == 0) { System.out.println("hi"); }
            }
        }
        """;
    assertEquals(1, run(src), "fis.read(buf) == 0 must fire");
  }

  @Test
  void doesNotFireOnReadEqualsMinusOne() {
    // The CORRECT check — should not fire
    String src =
        """
        import java.io.FileInputStream;
        class T {
            void m(FileInputStream fis, byte[] buf) throws Exception {
                if (fis.read(buf) == -1) { System.out.println("hi"); }
            }
        }
        """;
    assertEquals(0, run(src), "fis.read(buf) == -1 must NOT fire");
  }

  @Test
  void doesNotFireOnReadEqualsArbitraryNonzero() {
    // Comparing to a non-sentinel value is still
    // odd but not a CWE-253 false positive at
    // our curated-list level.
    String src =
        """
        import java.io.FileInputStream;
        class T {
            void m(FileInputStream fis, byte[] buf, int n) throws Exception {
                if (fis.read(buf) == n) { System.out.println("hi"); }
            }
        }
        """;
    // n is not an integer literal — the rule
    // requires RHS be a literal — must not fire.
    assertEquals(0, run(src), "== n (variable) must NOT fire");
  }

  @Test
  void firesOnReadNotEqualsZero() {
    // The != variant of the same bug
    String src =
        """
        import java.io.FileInputStream;
        class T {
            void m(FileInputStream fis, byte[] buf) throws Exception {
                if (fis.read(buf) != 0) { System.out.println("hi"); }
            }
        }
        """;
    assertEquals(1, run(src), "fis.read(buf) != 0 must fire");
  }

  @Test
  void doesNotFireOnUnrelatedMethod() {
    // length() is not in the curated list
    String src =
        """
        class T {
            void m(String s) {
                if (s.length() == 0) { System.out.println("empty"); }
            }
        }
        """;
    assertEquals(0, run(src), "s.length() == 0 must NOT fire (not in curated list)");
  }

  @Test
  void doesNotFireOutsideIfCondition() {
    // Same shape, but used in arithmetic (not
    // an if/while condition) — out of scope for
    // this rule.
    String src =
        """
        import java.io.FileInputStream;
        class T {
            int m(FileInputStream fis, byte[] buf) throws Exception {
                return fis.read(buf) == 0 ? 1 : 0;
            }
        }
        """;
    // The BinaryExpr's parent here is ConditionalExpr,
    // not IfStmt, so we don't fire. (The user can
    // still add the rule on a richer checker.)
    assertEquals(0, run(src), "ternary read(buf) == 0 must NOT fire");
  }
}

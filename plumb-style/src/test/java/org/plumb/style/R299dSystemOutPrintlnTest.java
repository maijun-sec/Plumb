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

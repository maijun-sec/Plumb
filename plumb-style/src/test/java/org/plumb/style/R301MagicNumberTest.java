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
import org.plumb.style.style.MagicNumberCheck;

/** MagicNumberCheck — hard-coded numeric literals should be named constants. */
class R301MagicNumberTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new MagicNumberCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-magic-number"))
        .count();
  }

  @Test
  void firesOnMagicNumber() {
    String src =
        """
        class T {
        void m() {
        int x = 42;
        }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "42 in local var init — should fire");
  }

  @Test
  void noFireOnIgnoredNumbers() {
    String src =
        """
        class T {
        void m() {
        int a = 0;
        int b = 1;
        int c = 2;
        int d = -1;
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "0, 1, 2, -1 are ignored by default");
  }

  @Test
  void noFireOnStaticFinal() {
    String src =
        """
        class T {
        static final int MAX = 100;
        }
        """;
    long n = run(src);
    assertEquals(0, n, "static final constant — no fire");
  }

  @Test
  void noFireOnForLoopIndex() {
    String src =
        """
        class T {
        void m() {
        for (int i = 0; i < 10; i++) {
        int x = i;
        }
        }
        }
        """;
    // 0 and 1 are ignored, 10 is in for-loop
    long n = run(src);
    assertEquals(0, n, "for-loop literals — exempt");
  }

  @Test
  void firesOnDoubleMagic() {
    String src =
        """
        class T {
        double m() {
        return 3.14;
        }
        }
        """;
    long n = run(src);
    assertEquals(1, n, "3.14 — should fire");
  }
}

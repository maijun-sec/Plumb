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
import org.plumb.style.design.JavaNCSSCheck;

/** JavaNCSSCheck — class/method/constructor statement count exceeds maximum. */
class R302JavaNCSSSTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new JavaNCSSCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-design-java-ncss"))
        .count();
  }

  @Test
  void noFireOnSmallMethod() {
    String src =
        """
        class T {
        void m() {
        int a = 1;
        int b = 2;
        int c = 3;
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "small method — no fire");
  }

  @Test
  void firesOnLongMethod() {
    // 65 statements in method body
    StringBuilder body = new StringBuilder("class T {\n void m() {\n");
    for (int i = 0; i < 65; i++) {
      body.append(" int v").append(i).append(" = ").append(i).append(";\n");
    }
    body.append(" }\n}\n");
    long n = run(body.toString());
    // 65 statements in method (max 60) + 1 fire on the class? Class has 66 stmts (method body +
    // class). class max 1500 so no.
    assertEquals(1, n, "method has 65 stmts > max 60 — should fire");
  }
}

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
import org.plumb.style.design.AbstractClassCouplingCheck;

/** AbstractClassCouplingCheck — class should not instantiate too many distinct types. */
class R298cAbstractClassCouplingTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new AbstractClassCouplingCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-design-abstract-coupling"))
        .count();
  }

  @Test
  void firesOnTooManyDistinctTypes() {
    // 21 distinct types → max 20 → fire once
    StringBuilder src = new StringBuilder("class T {\n");
    for (int i = 0; i < 21; i++) {
      src.append(" Object f").append(i).append(" = new Type").append(i).append("();\n");
    }
    src.append("}\n");
    // Need 21 actual distinct types to be referenced; provide them.
    for (int i = 0; i < 21; i++) {
      src.insert(0, "class Type" + i + " {}\n");
    }
    long n = run(src.toString());
    assertEquals(1, n, "should fire — 21 distinct types > max 20");
  }

  @Test
  void noFireOnFewTypes() {
    String src =
        """
        class A {}
        class B {}
        class C {}
        class T {
        Object a = new A();
        Object b = new B();
        Object c = new C();
        }
        """;
    long n = run(src);
    assertEquals(0, n, "3 types < max 20 — no fire");
  }

  @Test
  void noFireOnRepeatedTypes() {
    // Same type instantiated many times — still 1 distinct
    String src =
        """
        class A {}
        class T {
        void m() {
        new A();
        new A();
        new A();
        }
        }
        """;
    long n = run(src);
    assertEquals(0, n, "1 distinct type — no fire");
  }
}

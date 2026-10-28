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
import org.plumb.style.style.MissingDeprecatedCheck;

/**
 * MissingDeprecatedCheck ? every public method in a @Deprecated class should itself be @Deprecated.
 */
class R298fMissingDeprecatedTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new MissingDeprecatedCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-missing-deprecated"))
        .count();
  }

  @Test
  void firesOnUnmarkedPublicMethod() {
    String src =
        """
        @Deprecated
        class T {
        public void foo() {}
        }
        """;
    long n = run(src);
    assertEquals(1, n, "public method in @Deprecated class without @Deprecated");
  }

  @Test
  void noFireWhenAllMarked() {
    String src =
        """
        @Deprecated
        class T {
        @Deprecated
        public void foo() {}
        }
        """;
    long n = run(src);
    assertEquals(0, n, "all methods marked — no fire");
  }

  @Test
  void noFireOnNonDeprecatedClass() {
    String src =
        """
        class T {
        public void foo() {}
        }
        """;
    long n = run(src);
    assertEquals(0, n, "class not @Deprecated — no fire");
  }

  @Test
  void noFireOnPrivateMethods() {
    String src =
        """
        @Deprecated
        class T {
        private void helper() {}
        }
        """;
    long n = run(src);
    assertEquals(0, n, "private method — no fire (default ignorePrivate)");
  }
}

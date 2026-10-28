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
import org.plumb.style.style.ImportControlCheck;

/** ImportControlCheck — imports should be in canonical order: java, javax, other. */
class R298dImportControlTest {

  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
  private final JavaSourceParser parser = new JavaSourceParser();

  private long run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new ImportControlCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-import-control"))
        .count();
  }

  @Test
  void noFireOnCanonicalOrder() {
    String src =
        """
        import java.util.List;
        import java.util.Map;
        import javax.annotation.Nullable;
        import com.example.Foo;
        import org.bar.Baz;
        class T {}
        """;
    long n = run(src);
    assertEquals(0, n, "canonical order — no fire");
  }

  @Test
  void firesOnWrongGroupOrder() {
    // com.* before java.* — wrong group order
    String src =
        """
        import com.example.Foo;
        import java.util.List;
        class T {}
        """;
    long n = run(src);
    assertEquals(1, n, "java import after non-java — should fire");
  }

  @Test
  void firesOnAlphabeticalViolation() {
    // within java group: M before L — out of order
    String src =
        """
        import java.util.Map;
        import java.util.List;
        class T {}
        """;
    long n = run(src);
    assertEquals(1, n, "Map before List within java group — out of order");
  }
}

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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.performance.UseCollectionIsEmptyCheck;

/** S1155 UseCollectionIsEmptyCheck mirror. */
class R176SonarCollectionIsEmptyTest {

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new UseCollectionIsEmptyCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnSizeEqualsZero() {
    String src =
        "import java.util.*; class N {"
            + " void m(List<String> l) {"
            + " if (l.size() == 0) System.out.println(\"empty\");"
            + " }"
            + "}";
    assertThat(run(src)).as("R176: list.size() == 0 fires").isNotEmpty();
  }

  @Test
  void firesOnSizeGreaterThanZero() {
    String src =
        "import java.util.*; class N {"
            + " void m(List<String> l) {"
            + " if (l.size() > 0) System.out.println(\"not empty\");"
            + " }"
            + "}";
    assertThat(run(src)).as("R176: list.size() > 0 fires").isNotEmpty();
  }

  @Test
  void noFindingForIsEmptyCall() {
    String src =
        "import java.util.*; class N {"
            + " void m(List<String> l) {"
            + " if (l.isEmpty()) System.out.println(\"empty\");"
            + " }"
            + "}";
    assertThat(run(src)).as("R176: l.isEmpty() is the canonical idiom; no fire").isEmpty();
  }

  @Test
  void noFindingForUnrelatedSizeComparison() {
    // Comparing a `size` int to a non-zero literal is
    // not in scope (S1155 fires on == 0 / > 0 / etc.).
    // We pick a non-zero rhs so the pattern does not
    // match.
    String src =
        "import java.util.*; class N {"
            + " void m(List<String> l) {"
            + " if (l.size() > 5) System.out.println(\"big\");"
            + " }"
            + "}";
    assertThat(run(src)).as("R176: comparing to non-zero literal is not the bad idiom").isEmpty();
  }
}

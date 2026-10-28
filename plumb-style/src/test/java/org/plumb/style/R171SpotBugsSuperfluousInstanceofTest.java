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
import org.plumb.style.style.SuperfluousInstanceofCheck;

/** Plumb-side <code>SIO_SUPERFLUOUS_INSTANCEOF</code> mirror. */
class R171SpotBugsSuperfluousInstanceofTest {

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new SuperfluousInstanceofCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnRedundantCastInThenBlock() {
    // Canonical pattern: instanceof String
    // followed by (String) cast in the then-block.
    String src =
        "class N {"
            + " void m(Object x) {"
            + " if (x instanceof String) {"
            + " String s = (String) x;"
            + " s.length();"
            + " }"
            + " }"
            + "}";
    assertThat(run(src)).as("R171: redundant cast in then-block fires").isNotEmpty();
  }

  @Test
  void noFindingWhenThenBlockHasNoCast() {
    // instanceof check without redundant cast :
    // proper code, no finding.
    String src =
        "class N {"
            + " void m(Object x) {"
            + " if (x instanceof String) {"
            + " x.toString();"
            + " }"
            + " }"
            + "}";
    assertThat(run(src)).as("R171: instanceof without cast is clean").isEmpty();
  }

  @Test
  void noFindingForDifferentCastType() {
    // Cast type is different from instanceof type :
    // not redundant.
    String src =
        "class N {"
            + " void m(Object x) {"
            + " if (x instanceof String) {"
            + " Object o = (Object) x;"
            + " }"
            + " }"
            + "}";
    assertThat(run(src)).as("R171: cast to a different type is not redundant").isEmpty();
  }

  @Test
  void noFindingForPlainIfWithoutInstanceof() {
    // No instanceof : irrelevant to.
    String src =
        "class N {"
            + " void m(Object x) {"
            + " if (x != null) {"
            + " String s = (String) x;"
            + " }"
            + " }"
            + "}";
    assertThat(run(src)).as("R171: cast without instanceof is not in scope").isEmpty();
  }
}

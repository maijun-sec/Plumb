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

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.TestWithoutAssertionCheck;

/** S2699 "Tests should include assertions" mirror (plumb-style). */
class R203SonarTestNoAssertionTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<org.plumb.reporter.Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new TestWithoutAssertionCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnTestWithoutAssertion() {
    String src =
        "import org.junit.jupiter.api.Test;"
            + "class N {"
            + " @Test void testFoo() {"
            + " int x = 1 + 1;" // no assertion!
            + " }"
            + "}";
    assertThat(run(src)).as("R203: @Test without assertion fires").isNotEmpty();
  }

  @Test
  void noFireOnTestWithAssertion() {
    String src =
        "import static org.junit.jupiter.api.Assertions.assertEquals;"
            + "import org.junit.jupiter.api.Test;"
            + "class N {"
            + " @Test void testFoo() {"
            + " assertEquals(2, 1 + 1);"
            + " }"
            + "}";
    assertThat(run(src)).as("R203: @Test with assertion does not fire").isEmpty();
  }

  @Test
  void noFireOnNonTestMethod() {
    // Helper method that does computations, no
    // @Test annotation. Out of scope.
    String src = "class N {" + " void helper() {" + " int x = 1 + 1;" + " }" + "}";
    assertThat(run(src)).as("R203: non-test method does not fire").isEmpty();
  }

  @Test
  void noFireOnTestWithAssertThrows() {
    String src =
        "import static org.junit.jupiter.api.Assertions.assertThrows;import"
            + " org.junit.jupiter.api.Test;class N { @Test void testFoo() {"
            + " assertThrows(IllegalArgumentException.class, () -> { throw new"
            + " IllegalArgumentException(); }); }}";
    assertThat(run(src)).as("R203: assertThrows counts as assertion").isEmpty();
  }
}

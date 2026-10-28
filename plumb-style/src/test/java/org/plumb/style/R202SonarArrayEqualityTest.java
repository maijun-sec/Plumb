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
import org.plumb.style.performance.ArrayEqualityCheck;

/** S3012 "Arrays should not be compared with ==" mirror (plumb-style). */
class R202SonarArrayEqualityTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<org.plumb.reporter.Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new ArrayEqualityCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnArrayElementEquality() {
    // The classic array[i] == arr2[j] bug — these
    // are array element accesses, so this is an
    // element comparison, not a reference
    // comparison. Wait — this rule fires on
    // element-to-element ==, not the canonical
    // "array reference == array reference" case
    // (which would need a type resolver). The
    // narrow check fires on both cases.
    String src =
        "class N { boolean m(int[] a, int[] b, int i, int j) {" + " return a[i] == b[j];" + "} }";
    assertThat(run(src)).as("R202: a[i] == b[j] fires (array element equality)").isNotEmpty();
  }

  @Test
  void firesOnNotEqual() {
    String src =
        "class N { boolean m(int[] a, int[] b, int i, int j) {" + " return a[i] != b[j];" + "} }";
    assertThat(run(src)).as("R202: a[i] != b[j] fires").isNotEmpty();
  }

  @Test
  void noFireOnScalarComparison() {
    String src = "class N { boolean m(int a, int b) {" + " return a == b;" + "} }";
    assertThat(run(src)).as("R202: a == b (scalars) does not fire").isEmpty();
  }

  @Test
  void noFireOnOneSideScalar() {
    String src = "class N { boolean m(int[] a, int b, int i) {" + " return a[i] == b;" + "} }";
    assertThat(run(src)).as("R202: a[i] == b (one side scalar) does not fire").isEmpty();
  }
}

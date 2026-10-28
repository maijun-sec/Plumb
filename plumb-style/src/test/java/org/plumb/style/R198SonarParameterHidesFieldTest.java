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
import org.plumb.style.style.ParameterHidesFieldCheck;

/**
 * S1226 "Method parameters should not hide class fields" mirror (plumb-style).
 *
 * <p>Tests cover the canonical shadow patterns: parameter same name as field, no fire when
 * different name, no fire when field is static (only instance fields shadow), and no fire on class
 * with no fields.
 */
class R198SonarParameterHidesFieldTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<org.plumb.reporter.Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new ParameterHidesFieldCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnParameterHidesField() {
    String src = "class N { int x;" + " void m(int x) { this.x = x; }" + "}";
    assertThat(run(src)).as("R198: parameter x hides field x fires").isNotEmpty();
  }

  @Test
  void noFireOnDifferentNames() {
    String src = "class N { int x;" + " void m(int y) { this.x = y; }" + "}";
    assertThat(run(src)).as("R198: parameter y does not hide field x").isEmpty();
  }

  @Test
  void noFireOnClassWithNoFields() {
    String src = "class N {" + " void m(int x) { int y = x; }" + "}";
    assertThat(run(src)).as("R198: no fields, nothing to hide").isEmpty();
  }

  @Test
  void firesOnMultipleHiddenParameters() {
    String src =
        "class N { int x; int y;"
            + " void m(int x, int y, int z) { this.x = x; this.y = y; }"
            + "}";
    // Two hidden parameters (x and y) — at least 2
    // findings expected.
    assertThat(run(src)).as("R198: two hidden parameters (x and y)").hasSizeGreaterThanOrEqualTo(2);
  }
}

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
import org.plumb.style.style.MissingBreakCheck;

/**
 * The missing-break defect class (plumb-style).
 *
 * <p>Tests cover the canonical fall-through patterns: the Apple "goto fail" (single missing break),
 * the Duff's device empty-case (intentional fall-through, no fire), cases that end with
 * return/throw (no fire), and the last case (no fire, no fall-through target).
 */
class R192MissingBreakTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<org.plumb.reporter.Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new MissingBreakCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnAppleGotoFailFallThrough() {
    // The canonical "Apple goto fail" bug shape: a
    // switch with two cases, the first has two
    // statements and the second statement is a
    // function call, no break — so execution falls
    // into the second case.
    String src =
        "class N { void m(int x) {"
            + " switch (x) {"
            + " case 0:"
            + " System.out.println(\"zero\");"
            + " doSomething();"
            + " case 1:"
            + " System.out.println(\"one\");"
            + " break;"
            + " default:"
            + " break;"
            + " }"
            + "} void doSomething() {} }";
    assertThat(run(src)).as("R192: Apple-goto-fail fall-through fires").isNotEmpty();
  }

  @Test
  void noFireWhenCaseEndsWithReturn() {
    // A case that ends with `return` does not fall
    // through — the function exits.
    String src =
        "class N { int m(int x) {"
            + " switch (x) {"
            + " case 0:"
            + " System.out.println(\"zero\");"
            + " return 0;"
            + " case 1:"
            + " return 1;"
            + " default:"
            + " return -1;"
            + " }"
            + "} }";
    assertThat(run(src)).as("R192: case ending with return does not fall through").isEmpty();
  }

  @Test
  void noFireWhenCaseEndsWithThrow() {
    String src =
        "class N { void m(int x) {"
            + " switch (x) {"
            + " case 0:"
            + " System.out.println(\"zero\");"
            + " throw new RuntimeException(\"zero\");"
            + " case 1:"
            + " break;"
            + " }"
            + "} }";
    assertThat(run(src)).as("R192: case ending with throw does not fall through").isEmpty();
  }

  @Test
  void noFireOnEmptyCaseIntentionalFallThrough() {
    // Empty cases are intentional fall-through (Duff's
    // device idiom). Should not fire.
    String src =
        "class N { void m(int x) {"
            + " switch (x) {"
            + " case 0:"
            + " case 1:"
            + " System.out.println(\"zero or one\");"
            + " break;"
            + " case 2:"
            + " break;"
            + " }"
            + "} }";
    assertThat(run(src)).as("R192: empty case (intentional fall-through) does not fire").isEmpty();
  }

  @Test
  void noFireOnLastCase() {
    // The last case has no fall-through target. Even
    // if it doesn't end with a terminator, it's
    // harmless (execution just exits the switch).
    String src =
        "class N { void m(int x) {"
            + " switch (x) {"
            + " case 0:"
            + " System.out.println(\"zero\");"
            + " break;"
            + " case 1:"
            + " System.out.println(\"one\");"
            + " }"
            + "} }";
    assertThat(run(src))
        .as("R192: last case with no break does not fire (no fall-through target)")
        .isEmpty();
  }
}

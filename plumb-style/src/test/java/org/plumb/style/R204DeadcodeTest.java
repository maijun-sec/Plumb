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
import org.plumb.style.style.DeadcodeCheck;

/** Unreachable */
class R204DeadcodeTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<org.plumb.reporter.Finding> run(String src, AstChecker checker) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(checker).run(ctx);
    return ctx.findings();
  }

  // ============== UnreachableCode (Deadcode) ==============
  @Test
  void deadcode_firesAfterReturn() {
    // The `System.out.println("never");` is unreachable
    // because the previous `return 5;` exits the
    // method.
    String src = "class N { int m() {" + " return 5;" + " System.out.println(\"never\");" + "} }";
    assertThat(run(src, new DeadcodeCheck())).as("DEADCODE: code after return fires").isNotEmpty();
  }

  @Test
  void deadcode_firesAfterThrow() {
    // `System.out.println("never");` is unreachable
    // because the previous `throw` propagates out.
    String src =
        "class N { int m() {"
            + " throw new RuntimeException(\"bad\");"
            + " System.out.println(\"never\");"
            + "} }";
    assertThat(run(src, new DeadcodeCheck())).as("DEADCODE: code after throw fires").isNotEmpty();
  }

  @Test
  void deadcode_firesAfterBareReturn() {
    // `return;` (no value) also terminates the
    // block, so the next statement is unreachable.
    String src =
        "class N { void m() {" + " int x = 1;" + " return;" + " System.out.println(x);" + "} }";
    assertThat(run(src, new DeadcodeCheck()))
        .as("DEADCODE: code after bare return fires")
        .isNotEmpty();
  }

  @Test
  void deadcode_noFireOnReachableCode() {
    // No return/throw/break/continue — every
    // statement is reachable.
    String src = "class N { int m(int x) {" + " if (x > 0) { x = x * 2; }" + " return x;" + "} }";
    assertThat(run(src, new DeadcodeCheck()))
        .as("DEADCODE: no terminator before final stmt does not fire")
        .isEmpty();
  }
}

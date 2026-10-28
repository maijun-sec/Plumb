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

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.AvoidPrintStackTraceCheck;

/** AvoidPrintStackTraceCheck — e.printStackTrace() should be replaced with a logger. */
class R786AvoidPrintStackTraceCheckTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private long run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new AvoidPrintStackTraceCheck()).run(ctx);
    return ctx.findings().stream()
        .filter(f -> f.rule().ruleId().id().equals("ast-style-avoid-print-stack-trace"))
        .count();
  }

  @Test
  void firesOnCatchParamPrintStackTrace() {
    String src =
        "class N {\n"
            + "  void m() {\n"
            + "    try { throw new RuntimeException(); }\n"
            + "    catch (RuntimeException e) { e.printStackTrace(); }\n"
            + "  }\n"
            + "}";
    assertEquals(1, run(src), "catch param e.printStackTrace() should fire");
  }

  @Test
  void doesNotFireOnNonCatchParam() {
    // R786-P1-1-2026-10-18 regression test. Pre-fix, catchParamNames leaked across
    // methods: a previous method's catch param would be matched against a local
    // variable of the same name in the next method, causing a false positive.
    String src =
        "class N {\n"
            + "  void m1() {\n"
            + "    try { throw new RuntimeException(); }\n"
            + "    catch (RuntimeException e) { e.printStackTrace(); }\n"
            + "  }\n"
            + "  void m2() {\n"
            + "    RuntimeException e = new RuntimeException();\n"
            + "    e.printStackTrace();\n"
            + "  }\n"
            + "}";
    assertEquals(
        1, run(src), "Only m1's e.printStackTrace() is a catch param; m2's local `e` is not");
  }

  @Test
  void doesNotFireOnOtherMethod() {
    // Sanity: two methods, neither uses printStackTrace.
    String src =
        "class N {\n" + "  void m1() { int x = 1; }\n" + "  void m2() { int y = 2; }\n" + "}";
    assertEquals(0, run(src));
  }
}

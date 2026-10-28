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
package org.plumb.style.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.Finding;

/**
 * Tests for {@link ErrorMessageInfoLeakCheck}.
 *
 * <p>Covers: positive (printStackTrace fires), positive (throw new RuntimeException(e) fires),
 * negative (sink call but receiver is NOT the catch param ? does not fire), boundary
 * (RuntimeException with extra arg ? does not fire), unrelated (no exception ? does not fire).
 */
class ErrorMessageInfoLeakCheckTest {

  /**
   * Local harness because the test lives in org.plumb.style.error (the security harness is in the
   * security package). Mirrors the security harness shape but only dispatches CatchClause via
   * findAll.
   */
  private List<Finding> run(String src) {
    com.github.javaparser.JavaParser parser =
        new com.github.javaparser.JavaParser(
            new com.github.javaparser.ParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17));
    com.github.javaparser.ast.CompilationUnit cu = parser.parse(src).getResult().get();
    org.plumb.parser.ParsedSource ps =
        new org.plumb.parser.ParsedSource(
            java.nio.file.Path.of("Test.java"),
            src,
            cu,
            java.util.List.<com.github.javaparser.Problem>of());
    org.plumb.style.StyleContext ctx =
        new org.plumb.style.StyleContext(ps, new org.plumb.rules.RuleRegistry());
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new org.plumb.style.AstDispatcher().register(new ErrorMessageInfoLeakCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void printStackTraceInCatch_fires() {
    List<Finding> f =
        run(
            "class T { void m() { try { throw new RuntimeException(); } "
                + "catch (RuntimeException e) { e.printStackTrace(); } } }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-209");
  }

  @Test
  void throwNewRuntimeExceptionWithCatchParam_fires() {
    List<Finding> f =
        run(
            "class T { void m() { try { throw new RuntimeException(); } "
                + "catch (RuntimeException e) { "
                + "throw new RuntimeException(e); } } }");
    assertThat(f).hasSize(1);
  }

  @Test
  void getMessageOnNonCatchParam_doesNotFire() {
    // receiver is "x" but catch param is "e".
    List<Finding> f =
        run(
            "class T { void m() { try { throw new RuntimeException(); } "
                + "catch (RuntimeException e) { "
                + "Object x = new Object(); "
                + "x.toString(); } } }");
    assertThat(f).isEmpty();
  }

  @Test
  void throwNewExceptionWithExtraArg_doesNotFire() {
    // `new RuntimeException("msg", e)` — wraps but
    // with 2 args, not the direct catch-param wrap.
    List<Finding> f =
        run(
            "class T { void m() { try { throw new RuntimeException(); } "
                + "catch (RuntimeException e) { "
                + "throw new RuntimeException(\"msg\", e); } } }");
    assertThat(f).isEmpty();
  }

  @Test
  void unrelatedCode_doesNotFire() {
    List<Finding> f =
        run("class T { void m() { " + "System.out.println(\"no exception here\"); } }");
    assertThat(f).isEmpty();
  }
}

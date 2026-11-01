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
package org.plumb.style.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.AstDispatcher;
import org.plumb.style.StyleContext;

/**
 * CWE-571: a condition whose value is already decided is not a condition.
 *
 * <p>The shapes covered here are the ones a whole-program reader can decide without guessing: a
 * literal, a local or field that holds a constant and is never reassigned, and a method whose body
 * is a single constant return.
 */
class R1200AlwaysTrueConstantTest {

  private final AstChecker check = new AlwaysTrueCheck();
  private final ParserConfiguration cfg =
      new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  private final JavaParser parser = new JavaParser(cfg);

  private List<Finding> run(String src) {
    CompilationUnit cu = parser.parse(src).getResult().get();
    ParsedSource ps = new ParsedSource(Path.of("Test.java"), src, cu, List.<Problem>of());
    RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);
    StyleContext ctx = new StyleContext(ps, rules);
    new AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }

  // ── the decided conditions ────────────────────────────────────────────

  @Test
  void aBooleanFieldThatHoldsTrue_isAlwaysTrue() {
    List<Finding> f =
        run(
            "class T { private static final boolean FLAG = true; "
                + "void m() { if (FLAG) { System.out.println(1); } } }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).rule().cwe()).contains("CWE-571");
  }

  @Test
  void aNonFinalBooleanFieldThatIsNeverReassigned_isAlwaysTrue() {
    List<Finding> f =
        run(
            "class T { private boolean flag = true; "
                + "void m() { if (flag) { System.out.println(1); } } }");
    assertThat(f).hasSize(1);
  }

  @Test
  void aBooleanLocalThatHoldsTrue_isAlwaysTrue() {
    List<Finding> f =
        run("class T { void m() { boolean b = true; if (b) { System.out.println(1); } } }");
    assertThat(f).hasSize(1);
  }

  @Test
  void aMethodThatReturnsTrue_isAlwaysTrue() {
    List<Finding> f =
        run(
            "class T { private static boolean returnsTrue() { return true; } "
                + "void m() { if (returnsTrue()) { System.out.println(1); } } }");
    assertThat(f).hasSize(1);
  }

  @Test
  void twoDecidedConstantsComparedWithEquals_areAlwaysTrue() {
    List<Finding> f =
        run(
            "class T { private static final int FIVE = 5; "
                + "void m() { if (FIVE == 5) { System.out.println(1); } } }");
    assertThat(f).hasSize(1);
  }

  @Test
  void aDecidedStringComparedWithEquals_isAlwaysFalse() {
    List<Finding> f =
        run(
            "class T { void m() { String s = \"true\"; "
                + "if (!s.equals(\"true\")) { System.out.println(1); } } }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).message()).contains("always false");
  }

  @Test
  void twoLocalConstantsComparedWithEquals_areAlwaysTrue() {
    List<Finding> f =
        run(
            "class T { void m() { int a = 30; int b = 31; "
                + "if (a == (b - 1)) { System.out.println(1); } } }");
    assertThat(f).hasSize(1);
  }

  // ── the shapes that are NOT decided ───────────────────────────────────

  @Test
  void anUnknownConditionIsNotReported() {
    List<Finding> f = run("class T { void m(boolean b) { if (b) { System.out.println(1); } } }");
    assertThat(f).isEmpty();
  }

  @Test
  void aFieldThatIsReassignedIsNotDecided() {
    List<Finding> f =
        run(
            "class T { private boolean flag = true; "
                + "void m() { flag = readFlag(); if (flag) { System.out.println(1); } } "
                + "boolean readFlag() { return false; } }");
    assertThat(f).isEmpty();
  }

  @Test
  void aLocalThatIsReassignedIsNotDecided() {
    List<Finding> f =
        run(
            "class T { void m() { boolean b = true; b = read(); "
                + "if (b) { System.out.println(1); } } boolean read() { return false; } }");
    assertThat(f).isEmpty();
  }

  @Test
  void aMethodWithTwoReturnsIsNotDecided() {
    List<Finding> f =
        run(
            "class T { private static boolean pick(boolean c) { if (c) { return true; } "
                + "return false; } void m() { if (pick(true)) { System.out.println(1); } } }");
    assertThat(f).isEmpty();
  }

  @Test
  void aMethodOfAnotherClassIsNotResolved() {
    // The other class's body is not available here, so the answer would be a guess.
    List<Finding> f =
        run(
            "class T { void m() { if (Other.flag()) { System.out.println(1); } } } "
                + "class Other { static boolean flag() { return true; } }");
    assertThat(f).isEmpty();
  }

  @Test
  void twoDifferentConstantsComparedWithEquals_areNotAlwaysTrue() {
    // 5 == 6 is always FALSE, which is a different defect class.
    List<Finding> f =
        run(
            "class T { private static final int FIVE = 5; "
                + "private static final int SIX = 6; "
                + "void m() { if (FIVE == SIX) { System.out.println(1); } } }");
    assertThat(f).isEmpty();
  }

  @Test
  void aLocalOfAnotherMethodIsNotVisible() {
    List<Finding> f =
        run(
            "class T { void a() { boolean b = true; } "
                + "void c() { if (b) { System.out.println(1); } } }");
    assertThat(f).isEmpty();
  }
}

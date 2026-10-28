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
/* R790c unit test for NullDerefFromReturnCheck tightening. */
package org.plumb.style.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.StyleContext;

class R790cNullDerefFromReturnCheckTest {

  private static StyleContext ctxFor(String src) {
    JavaParser parser = new JavaParser();
    ParseResult<CompilationUnit> result = parser.parse(src);
    assertThat(result.isSuccessful()).as("parse").isTrue();
    ParsedSource ps =
        new ParsedSource(
            java.nio.file.Paths.get("Test.java"),
            "Test.java",
            result.getResult().get(),
            new ArrayList<>());
    return new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
  }

  private static List<Finding> run(String src) {
    StyleContext ctx = ctxFor(src);
    try {
      // R-816: use AstDispatcher so per-node visit overloads fire.
      new org.plumb.style.AstDispatcher()
          .register(NullDerefFromReturnCheck.class.getDeclaredConstructor().newInstance())
          .run(ctx);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return ctx.findings();
  }

  @Test
  void freshObjectCreationSuppressesFire() {
    // `Vector v = new Vector<>(5); v.add(...)` is non-null.
    String src =
        "import java.util.Vector;\n"
            + "class T {\n"
            + "  void ok() {\n"
            + "    Vector<String> v = new Vector<>(5);\n"
            + "    v.add(\"hello\");\n"
            + "  }\n"
            + "}\n";
    assertThat(run(src)).isEmpty();
  }

  @Test
  void reassignNewObjectSuppressesFire() {
    // `x = new ArrayList<>()` then `x.add(...)` is non-null.
    String src =
        "import java.util.ArrayList;\n"
            + "class T {\n"
            + "  void ok() {\n"
            + "    ArrayList<String> x;\n"
            + "    x = new ArrayList<>();\n"
            + "    x.add(\"foo\");\n"
            + "  }\n"
            + "}\n";
    assertThat(run(src)).isEmpty();
  }

  @Test
  void stringLiteralAssignmentSuppressesFire() {
    // `String s = "literal"; s.trim();` — non-null.
    String src =
        "class T {\n"
            + "  void ok() {\n"
            + "    String s = \"hello\";\n"
            + "    s.trim();\n"
            + "  }\n"
            + "}\n";
    assertThat(run(src)).isEmpty();
  }

  @Test
  void intLiteralAssignmentSuppressesFire() {
    // `int n = 5; (n)` — n is dereferenced; int can never be null.
    // Use a function call as a stand-in for the use site.
    String src =
        "class T {\n"
            + "  void ok() {\n"
            + "    int n = 5;\n"
            + "    String s = String.valueOf(n);\n"
            + "  }\n"
            + "}\n";
    assertThat(run(src)).isEmpty();
  }

  @Test
  void nullCheckedTernarySuppressesFire() {
    // `x = (x == null) ? null : x; x.toString();` — self-
    // null-checked ternary, both branches are `x`, so the
    // result is non-null.
    String src =
        "class T {\n"
            + "  void ok(Object x) {\n"
            + "    x = (x == null) ? null : x;\n"
            + "    x.toString();\n"
            + "  }\n"
            + "}\n";
    assertThat(run(src)).isEmpty();
  }

  @Test
  void foreignNullCheckTernaryDoesNotSuppress() {
    // `x = (a == null) ? null : a;` — checks `a`, not `x`; the
    // result is potentially null. We must NOT suppress.
    String src =
        "class T {\n"
            + "  void bad(Object a) {\n"
            + "    Object x = (a == null) ? null : a;\n"
            + "    x.toString();\n"
            + "  }\n"
            + "}\n";
    assertThat(run(src)).hasSize(1);
  }

  @Test
  void earlyReturnGuardSuppressesFire() {
    // `if (x == null) { return; }` then `x.toString();` — non-null.
    String src =
        "class T {\n"
            + "  void ok(Object x) {\n"
            + "    if (x == null) { return; }\n"
            + "    x.toString();\n"
            + "  }\n"
            + "}\n";
    assertThat(run(src)).isEmpty();
  }

  @Test
  void earlyThrowGuardSuppressesFire() {
    // `if (x == null) { throw new RuntimeException(); }` then use.
    String src =
        "class T {\n"
            + "  void ok(Object x) {\n"
            + "    if (x == null) { throw new RuntimeException(); }\n"
            + "    x.toString();\n"
            + "  }\n"
            + "}\n";
    assertThat(run(src)).isEmpty();
  }

  @Test
  void ifNullGuardStillWorks() {
    // Existing: `if (x != null) { x.toString(); }` is suppressed.
    String src =
        "class T {\n"
            + "  void ok(Object x) {\n"
            + "    if (x != null) {\n"
            + "      x.toString();\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
    assertThat(run(src)).isEmpty();
  }

  @Test
  void unknownSourceStillFires() {
    // `x = someMethod(); x.toString();` without null check fires.
    String src =
        "class T {\n"
            + "  Object someMethod() { return null; }\n"
            + "  void bad() {\n"
            + "    Object x = someMethod();\n"
            + "    x.toString();\n"
            + "  }\n"
            + "}\n";
    assertThat(run(src)).hasSize(1);
    assertThat(run(src).get(0).rule().ruleId().id()).isEqualTo("ast-style-null-deref-from-return");
  }
}

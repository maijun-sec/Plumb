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
package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.printer.TacPrinter;

class InnerClassTranslationTest {

  @Test
  void innerClassMethodsAppearAsSeparateFunctions() {
    String src =
        "class Outer { "
            + "class Inner { "
            + "  int compute(int x) { return x + 1; } "
            + "} "
            + "}";
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("I.java"), src);
    var program = new TacTranslator(ps).translate();
    TacPrinter.dump(program);
    // The inner class's method is now flattened as a top-level-ish
    // function; its signature is prefixed with the class name.
    boolean hasInner =
        program.functions().stream()
            .anyMatch(f -> f.name().contains("Inner") || f.signature().contains("Inner"));
    assertThat(hasInner)
        .as("inner class method should be a function\n" + TacPrinter.dump(program))
        .isTrue();
  }

  @Test
  void anonymousClassLowered() {
    // ObjectCreationExpr of an anonymous class is a NEW in our IR
    // anyway; we just want to make sure it doesn't crash.
    String src = "class T { Runnable r = new Runnable() { public void run() { } }; }";
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("I.java"), src);
    var program = new TacTranslator(ps).translate();
    assertThat(program).isNotNull();
  }

  // ─── the enclosing class of a nested type ──────────────────

  /**
   * A nested type's functions must name their enclosing class, or the DFA engine cannot resolve the
   * file they live in.
   *
   * <p>THE DEFECT THIS PINS. The translator passed {@code null} as the enclosing class of every
   * nested type's callables, on the reasoning that the nested method's NAME already encodes the
   * outer scope. That reasoning was right that the lookup would miss, and wrong that missing it was
   * harmless: with no enclosing class the engine's per-class source-path map had no entry for the
   * nested type, so the reporter could not fill in the finding's file and the finding was emitted
   * with {@code filePath="unknown"}.
   *
   * <p>A finding that does not know its file is a finding nobody can act on: the Juliet harness
   * matches a case to a finding BY FILE, so such a finding stopped matching its case and the case
   * was scored as a miss.
   */
  @Test
  void aNestedTypeNamesItsEnclosingClass() {
    String src =
        "class Outer { "
            + "static class Inner { "
            + "  int compute(int x) { return x + 1; } "
            + "} "
            + "}";
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("I.java"), src);
    var program = new TacTranslator(ps).translate();

    var inner =
        program.functions().stream()
            .filter(f -> f.name() != null && f.name().contains("Inner"))
            .findFirst();

    assertThat(inner)
        .as("the nested class's method must be translated\n" + TacPrinter.dump(program))
        .isPresent();
    assertThat(inner.get().enclosingClass())
        .as("the nested class's method must name the class that declares it")
        .isEqualTo("Outer$Inner");
  }

  /** A top-level type's enclosing class is unchanged: its own short name. */
  @Test
  void aTopLevelTypeNamesItsEnclosingClass() {
    String src = "class Top { int compute(int x) { return x + 1; } }";
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("I.java"), src);
    var program = new TacTranslator(ps).translate();

    var top = program.functions().stream().filter(f -> "compute".equals(f.name())).findFirst();

    assertThat(top)
        .as("the top-level method must be translated\n" + TacPrinter.dump(program))
        .isPresent();
    assertThat(top.get().enclosingClass()).isEqualTo("Top");
  }

  /** A doubly-nested type names the full chain, so two same-named inner classes stay distinct. */
  @Test
  void aDoublyNestedTypeNamesTheWholeChain() {
    String src =
        "class Outer { "
            + "static class Middle { "
            + "  static class Inner { int compute(int x) { return x + 1; } } "
            + "} "
            + "}";
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("I.java"), src);
    var program = new TacTranslator(ps).translate();

    var inner =
        program.functions().stream()
            .filter(f -> f.name() != null && f.name().endsWith("compute"))
            .findFirst();

    assertThat(inner)
        .as("the innermost method must be translated\n" + TacPrinter.dump(program))
        .isPresent();
    assertThat(inner.get().enclosingClass()).isEqualTo("Outer$Middle$Inner");
  }
}

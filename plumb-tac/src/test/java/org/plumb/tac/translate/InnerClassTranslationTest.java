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
}

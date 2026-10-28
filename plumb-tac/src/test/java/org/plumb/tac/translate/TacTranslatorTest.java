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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.printer.TacPrinter;

class TacTranslatorTest {

  private TacProgram translate(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("Test.java"), src);
    return new TacTranslator(ps).translate();
  }

  @Test
  void translatesSimpleArithmetic() {
    TacProgram p = translate("class T { int m(int a, int b) { int c = a + b; return c; } }");
    assertThat(p.functions()).hasSize(1);
    TacFunction fn = p.functions().get(0);
    assertThat(fn.params()).hasSize(2);
    // Expected: MOVE param a, MOVE param b, BINOP a+b, MOVE c=t, RETURN t
    // Parameter locals (a, b, this) are added by the translator.
    assertThat(fn.instructions().stream().anyMatch(i -> i.kind == TacInstruction.Kind.BINOP))
        .isTrue();
    assertThat(fn.instructions().stream().anyMatch(i -> i.kind == TacInstruction.Kind.RETURN))
        .isTrue();
  }

  @Test
  void translatesIfElse() {
    String src = "class T { int m(int x) { if (x > 0) { return 1; } else { return 0; } } }";
    TacProgram p = translate(src);
    TacFunction fn = p.functions().get(0);
    long branches =
        fn.instructions().stream().filter(i -> i.kind == TacInstruction.Kind.BRANCH).count();
    long labels =
        fn.instructions().stream().filter(i -> i.kind == TacInstruction.Kind.LABEL).count();
    long jumps = fn.instructions().stream().filter(i -> i.kind == TacInstruction.Kind.JUMP).count();
    assertThat(branches).as("if/else has one BRANCH").isEqualTo(1);
    assertThat(labels).as("if/else produces L_THEN / L_ELSE / L_END").isGreaterThanOrEqualTo(3);
    assertThat(jumps).as("if/else has a jump to end").isGreaterThanOrEqualTo(1);
  }

  @Test
  void translatesWhile() {
    String src =
        "class T { int m(int n) { int s = 0; int i = 0; while (i < n) { s = s + i; i = i + 1; }"
            + " return s; } }";
    TacProgram p = translate(src);
    TacFunction fn = p.functions().get(0);
    // while emits: LABEL cond, BINOP cmp, BRANCH, LABEL body, body, JUMP cond, LABEL end
    assertThat(fn.instructions().stream().anyMatch(i -> i.kind == TacInstruction.Kind.LABEL))
        .isTrue();
    assertThat(fn.instructions().stream().filter(i -> i.kind == TacInstruction.Kind.BRANCH).count())
        .isEqualTo(1);
  }

  @Test
  void translatesMethodCall() {
    String src = "class T { void m() { System.out.println(\"hi\"); } }";
    TacProgram p = translate(src);
    TacFunction fn = p.functions().get(0);
    assertThat(
            fn.instructions().stream()
                .anyMatch(
                    i ->
                        i.kind == TacInstruction.Kind.CALL
                            && i.calleeSig != null
                            && i.calleeSig.contains("println")))
        .isTrue();
  }

  @Test
  void translatesObjectCreation() {
    String src = "class T { void m() { Object o = new Object(); } }";
    TacProgram p = translate(src);
    TacFunction fn = p.functions().get(0);
    assertThat(fn.instructions().stream().anyMatch(i -> i.kind == TacInstruction.Kind.NEW))
        .isTrue();
  }

  @Test
  void translatesArrayCreation() {
    String src = "class T { void m() { int[] a = new int[10]; } }";
    TacProgram p = translate(src);
    TacFunction fn = p.functions().get(0);
    assertThat(fn.instructions().stream().anyMatch(i -> i.kind == TacInstruction.Kind.NEWARRAY))
        .isTrue();
  }

  @Test
  void translatesTryCatch() {
    String src =
        "class T { void m() { try { int x = 1; } catch (RuntimeException e) { int y = 2; } } }";
    TacProgram p = translate(src);
    // Should not throw, and function exists.
    assertThat(p.functions()).isNotEmpty();
  }

  @Test
  void translatesMultipleMethods() {
    String src = "class T { int a() { return 1; } int b(int x) { return x + 1; } }";
    TacProgram p = translate(src);
    assertThat(p.functions()).hasSize(2);
  }

  @Test
  void debugDumpRendersCleanly() throws IOException {
    String src = "class T { int m(int a, int b) { if (a > b) { return a; } return b; } }";
    TacProgram p = translate(src);
    String dump = TacPrinter.dump(p);
    Files.writeString(
        Path.of("target", "TacTranslatorTest.dmp"), dump); // helps when running with -X
    assertThat(dump).contains("function");
  }
}

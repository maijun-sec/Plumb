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
package org.plumb.tac.bytecode;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The bytecode front end must record the class a function belongs to.
 *
 * <p>The call graph's owner-aware index is keyed by {@code owner.method/arity}, and the owner comes
 * from {@link TacFunction#enclosingClass()}. A function without one is not in that index at all, so
 * a call to it is resolved by bare name across the whole program -- which guesses between
 * same-named methods of different classes.
 *
 * <p>The spelling matters as much as the presence. The bytecode lane types {@code this} with the
 * declaring class's fully qualified name, so the enclosing class has to be spelled the same way: a
 * rule that compares the receiver's type with the declaring class has to see one spelling on both
 * sides.
 */
class BytecodeDeclaringClassTest {

  @Test
  void everyFunctionOfAClassCarriesThatClass(@TempDir Path tmp) throws IOException {
    TacProgram program = analyzeOverloadDemo(tmp);

    assertThat(program.functions()).isNotEmpty();
    assertThat(program.functions())
        .filteredOn(f -> f.enclosingClass() == null || f.enclosingClass().isEmpty())
        .as("no function of a scanned class may be without its declaring class")
        .isEmpty();

    assertThat(program.functions())
        .allSatisfy(
            f ->
                assertThat(f.enclosingClass())
                    .as("the declaring class of " + f.signature())
                    .isEqualTo("bytecode.OverloadDemo"));
  }

  @Test
  void theReceiverOfACallOnThisIsTypedWithTheSameClass(@TempDir Path tmp) throws IOException {
    TacProgram program = analyzeOverloadDemo(tmp);

    // The `this` local is not in the function's locals -- the front end keeps it out of
    // that list on purpose -- so the receiver is looked for where it actually shows up:
    // in the instructions.
    int receivers = 0;
    for (TacFunction fn : program.functions()) {
      for (TacInstruction ins : fn.instructions()) {
        for (TacOperand operand : operandsOf(ins)) {
          if (!(operand instanceof TacOperand.TacVar var)) continue;
          if (!"this".equals(var.name())) continue;
          receivers++;
          assertThat(var.type())
              .as("the type of `this` in " + fn.signature())
              .isEqualTo(TacType.ofClass(fn.enclosingClass()));
        }
      }
    }
    assertThat(receivers)
        .as("the demo reads a field, so its bytecode has a receiver to type")
        .isPositive();
  }

  private static List<TacOperand> operandsOf(TacInstruction ins) {
    List<TacOperand> out = new java.util.ArrayList<>();
    if (ins.dest != null) out.add(ins.dest);
    if (ins.lhs1 != null) out.add(ins.lhs1);
    if (ins.lhs2 != null) out.add(ins.lhs2);
    if (ins.args != null) out.addAll(ins.args);
    return out;
  }

  private static TacProgram analyzeOverloadDemo(Path tmp) throws IOException {
    Path src = locateOverloadDemo();
    assertThat(src).exists();

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    int rc =
        compiler.run(
            System.in,
            System.out,
            System.err,
            "-d",
            tmp.toString(),
            "-encoding",
            "UTF-8",
            src.toString());
    assertThat(rc).isEqualTo(0);

    List<TacProgram> programs = new BytecodeAnalyzer(tmp).analyze();
    return programs.stream()
        .filter(p -> "bytecode.OverloadDemo".equals(p.typeName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("OverloadDemo produced no TAC program"));
  }

  private static Path locateOverloadDemo() {
    Path fromCwd = Path.of("src", "test", "resources", "bytecode", "OverloadDemo.java");
    if (Files.exists(fromCwd)) return fromCwd.toAbsolutePath();
    return Path.of("plumb-tac", "src", "test", "resources", "bytecode", "OverloadDemo.java")
        .toAbsolutePath();
  }
}

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
import java.util.Optional;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * The bytecode front end must not let a compiler-generated bridge method stand in for the method it
 * forwards to.
 *
 * <p>The TAC function key is {@code class.name(arity)}. It does not carry the return type, so a
 * covariant override and the bridge javac adds beside it land on ONE key, and only one of the two
 * bodies can be kept. The bridge's body is a call to the method it forwards to -- a call to a
 * method of its own name on {@code this} -- so when the bridge is the one that survives, the class
 * holds a self-call that no source file contains, and the method's real body is gone.
 *
 * <p>Measured on a real artifact: of 89 uncontrolled-recursion findings in one JAR, 17 were
 * self-calls on {@code this} inside the same class, and 8 of those were bridge methods standing in
 * for the method they forward to.
 */
class BytecodeBridgeMethodTest {

  @Test
  void covariantOverrideKeepsItsOwnBodyInsteadOfTheCompilerGeneratedBridge(@TempDir Path tmp)
      throws IOException {
    Path src = locateBridgeDemo();
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
    assertThat(
            Files.walk(tmp)
                .anyMatch(p -> p.getFileName().toString().equals("BridgeDemo$Child.class")))
        .as("BridgeDemo$Child.class should be somewhere under " + tmp)
        .isTrue();

    List<TacProgram> programs = new BytecodeAnalyzer(tmp).analyze();
    Optional<TacProgram> child =
        programs.stream().filter(p -> "bytecode.BridgeDemo$Child".equals(p.typeName())).findFirst();
    assertThat(child).isPresent();
    TacProgram program = child.get();

    // The bridge and the method it forwards to share one key, so exactly one function carries it.
    assertThat(program.functions())
        .filteredOn(f -> "bytecode.BridgeDemo$Child.produce(0)".equals(f.signature()))
        .hasSize(1);

    TacFunction produce = program.function("bytecode.BridgeDemo$Child.produce(0)");
    assertThat(produce).isNotNull();

    // The bridge's whole body is a call to the method it forwards to. If that body is the one
    // that survives, the class calls a method of its own name on `this`.
    assertThat(produce.instructions())
        .noneMatch(
            i ->
                i.kind == TacInstruction.Kind.CALL
                    && "bytecode.BridgeDemo$Child.produce/0".equals(i.calleeSig));

    // The body that has to survive is the real one: it returns the constant "child".
    assertThat(produce.instructions())
        .anyMatch(
            i ->
                i.kind == TacInstruction.Kind.RETURN
                    && i.lhs1 instanceof TacOperand.Const c
                    && c.kind() == TacOperand.Const.Kind.STRING
                    && "child".equals(c.value()));

    // The class that declares the widened method keeps its own body: it allocates.
    TacProgram base =
        programs.stream()
            .filter(p -> "bytecode.BridgeDemo".equals(p.typeName()))
            .findFirst()
            .orElseThrow();
    TacFunction baseProduce = base.function("bytecode.BridgeDemo.produce(0)");
    assertThat(baseProduce).isNotNull();
    assertThat(baseProduce.instructions()).anyMatch(i -> i.kind == TacInstruction.Kind.NEW);
  }

  private static Path locateBridgeDemo() {
    Path fromCwd = Path.of("src", "test", "resources", "bytecode", "BridgeDemo.java");
    if (Files.exists(fromCwd)) return fromCwd.toAbsolutePath();
    return Path.of("plumb-tac", "src", "test", "resources", "bytecode", "BridgeDemo.java")
        .toAbsolutePath();
  }
}

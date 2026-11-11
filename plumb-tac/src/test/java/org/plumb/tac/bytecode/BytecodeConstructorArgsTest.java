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
import org.plumb.tac.ir.TacProgram;

/**
 * A construction carries its constructor's arguments in the bytecode lane.
 *
 * <p>Jimple has no constructor call: {@code new Holder(value)} is an allocation plus a separate
 * special-invoke that runs the constructor, and the argument rides on the invoke. The source front
 * ends put the arguments on the NEW, and that is the shape the dataflow engine reads -- it
 * propagates taint from a constructor's arguments into the object the NEW builds. An allocation
 * with no arguments stops a tainted value at the constructor: the object it built looked clean, and
 * so did every later use of that object.
 */
class BytecodeConstructorArgsTest {

  @Test
  void newCarriesTheConstructorArguments(@TempDir Path tmp) throws IOException {
    Path src = locateCtorArgDemo();
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
    Optional<TacProgram> demo =
        programs.stream().filter(p -> "bytecode.CtorArgDemo".equals(p.typeName())).findFirst();
    assertThat(demo).isPresent();

    TacFunction make = demo.get().function("bytecode.CtorArgDemo.make(java.lang.String)");
    assertThat(make).isNotNull();

    List<TacInstruction> allocations =
        make.instructions().stream().filter(i -> i.kind == TacInstruction.Kind.NEW).toList();
    assertThat(allocations).hasSize(1);
    TacInstruction allocation = allocations.get(0);

    // The allocation names the class it builds.
    assertThat(allocation.ctorType).isNotNull();
    assertThat(allocation.ctorType.className()).endsWith("CtorArgDemo$Holder");

    // And it carries the constructor's own argument -- one argument, and not the receiver.
    assertThat(allocation.args).hasSize(1);
  }

  private static Path locateCtorArgDemo() {
    Path fromCwd = Path.of("src", "test", "resources", "bytecode", "CtorArgDemo.java");
    if (Files.exists(fromCwd)) return fromCwd.toAbsolutePath();
    return Path.of("plumb-tac", "src", "test", "resources", "bytecode", "CtorArgDemo.java")
        .toAbsolutePath();
  }
}

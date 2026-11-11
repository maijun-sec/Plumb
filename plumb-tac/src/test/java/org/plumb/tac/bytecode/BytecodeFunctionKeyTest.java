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
import org.plumb.tac.ir.CalleeKeyNormalizer;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * The bytecode front end's function key must name a method by its parameters, not by a count.
 *
 * <p>The key is the parenthesised part of the function's signature. Every reader of that part
 * counts the entries it finds -- {@link CalleeKeyNormalizer} and the call graph's bare-name index
 * both do -- so the bytecode front end has to put a PARAMETER LIST there. It used to put an arity
 * NUMBER there, and a number reads as a single parameter named "2".
 *
 * <p>Two things follow, and both are silent:
 *
 * <ul>
 *   <li>every bytecode method's key claims an arity of one, so a call to a method with any other
 *       number of parameters never meets its declaration and the edge is never built;
 *   <li>two methods that share a name and an arity share a key, so one of the two bodies is dropped
 *       and never analysed.
 * </ul>
 */
class BytecodeFunctionKeyTest {

  @Test
  void twoMethodsThatShareANameAndAnArityKeepBothBodies(@TempDir Path tmp) throws IOException {
    TacProgram program = analyzeOverloadDemo(tmp);

    assertThat(program.functions())
        .filteredOn(f -> f.signature().startsWith("bytecode.OverloadDemo.pick("))
        .as("both overloads must survive: the key names them by their parameters")
        .hasSize(2);

    assertThat(program.function("bytecode.OverloadDemo.pick(java.lang.String)"))
        .as("the narrow overload keeps its own body")
        .isNotNull();
    assertThat(program.function("bytecode.OverloadDemo.pick(java.lang.Object)"))
        .as("the wide overload keeps its own body")
        .isNotNull();
  }

  @Test
  void theKeyOfAMethodReportsItsOwnArity(@TempDir Path tmp) throws IOException {
    TacProgram program = analyzeOverloadDemo(tmp);

    assertThat(
            canonicalKeyOf(
                program, "bytecode.OverloadDemo.join(java.lang.String,java.lang.String)"))
        .as("a two-parameter method must not be keyed as a one-parameter method")
        .isEqualTo("bytecode.OverloadDemo.join/2");
    assertThat(canonicalKeyOf(program, "bytecode.OverloadDemo.nothing()"))
        .as("a method with no parameters must not be keyed as a one-parameter method")
        .isEqualTo("bytecode.OverloadDemo.nothing/0");
  }

  private static String canonicalKeyOf(TacProgram program, String signature) {
    TacFunction fn = program.function(signature);
    assertThat(fn).as("the function " + signature + " must exist").isNotNull();
    return CalleeKeyNormalizer.canonicalCalleeKey(fn.signature());
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

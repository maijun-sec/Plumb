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
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

/**
 * A call must carry the parameter types of the overload it selected.
 *
 * <p>A callee signature names the method by its name and by its number of parameters. Two overloads
 * share both -- {@code pick(String)} and {@code pick(Object)} -- so the signature alone cannot say
 * which of them a call went to. A rule that asks "is this call the method I am looking at?" and
 * answers from the signature alone reads a call to one overload as a call to the other.
 *
 * <p>The bytecode itself does not have that ambiguity: the constant pool holds the chosen overload,
 * so the call site knows. The IR has to carry that knowledge, or the rule cannot use it.
 */
class BytecodeCalleeParamTypesTest {

  @Test
  void twoCallsWithTheSameNameAndArityCarryDifferentParameterTypes(@TempDir Path tmp)
      throws IOException {
    TacProgram program = analyzeOverloadDemo(tmp);

    TacFunction both = program.function("bytecode.OverloadDemo.pickBoth(java.lang.String)");
    assertThat(both).as("the calling method must exist").isNotNull();

    assertThat(paramTypesOfCallsTo(both, "bytecode.OverloadDemo.pick/1"))
        .as(
            "the two calls name the same method with the same arity; only the parameter types "
                + "of the callee tell them apart")
        .containsExactlyInAnyOrder(List.of("java.lang.String"), List.of("java.lang.Object"));
  }

  @Test
  void aCallToTheSameOverloadCarriesTheEnclosingFunctionsParameterTypes(@TempDir Path tmp)
      throws IOException {
    TacProgram program = analyzeOverloadDemo(tmp);

    TacFunction twice = program.function("bytecode.OverloadDemo.pickTwice(java.lang.String)");
    assertThat(twice).as("the calling method must exist").isNotNull();

    List<List<String>> calls = paramTypesOfCallsTo(twice, "bytecode.OverloadDemo.pick/1");
    assertThat(calls).as("both calls select the same overload").hasSize(2);
    assertThat(calls)
        .as(
            "the callee's parameter types must equal the enclosing function's, so that a rule "
                + "can tell 'the same overload' from 'another overload with the same name'")
        .allSatisfy(types -> assertThat(types).isEqualTo(paramTypesOfSignature(twice.signature())));
  }

  /** The parameter types of every call in {@code fn} that names {@code calleeSig}. */
  private static List<List<String>> paramTypesOfCallsTo(TacFunction fn, String calleeSig) {
    List<List<String>> out = new ArrayList<>();
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind != TacInstruction.Kind.CALL) {
        continue;
      }
      if (calleeSig.equals(ins.calleeSig)) {
        out.add(ins.knownCalleeParamTypes());
      }
    }
    return out;
  }

  /** The parameter list of a signature, as the list the callee carries. */
  private static List<String> paramTypesOfSignature(String signature) {
    int open = signature.indexOf('(');
    int close = signature.lastIndexOf(')');
    assertThat(open).as("the signature must carry a parameter list: " + signature).isPositive();
    assertThat(close)
        .as("the signature must carry a parameter list: " + signature)
        .isGreaterThan(open);
    String inner = signature.substring(open + 1, close).trim();
    if (inner.isEmpty()) {
      return List.of();
    }
    return List.of(inner.split(","));
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

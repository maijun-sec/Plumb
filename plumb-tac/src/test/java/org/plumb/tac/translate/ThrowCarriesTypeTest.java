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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.bytecode.BytecodeAnalyzer;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

/**
 * The THROW instruction carries the type of the value it raises.
 *
 * <p><b>Why the front end has to do this.</b> The CFG builder decides which catch handler an
 * exception reaches by comparing the thrown type against the handler's declared type. A THROW
 * without a type says nothing, so every handler stays reachable and the engine cannot tell which
 * catch parameter inherits the thrown value's taint.
 *
 * <p><b>Why the qualified name matters.</b> The exception hierarchy is keyed by the qualified name,
 * so "IOException" decides nothing -- a program may declare its own class with that name. The
 * source lane asks the symbol solver for the resolved type, and the bytecode lane reads the local's
 * declared type out of the class file.
 */
@DisplayName("TAC: a throw carries the type it raises")
class ThrowCarriesTypeTest {

  private static TacFunction translate(String source) {
    ParsedSource parsed = new JavaSourceParser().parseFromString(Path.of("Test.java"), source);
    return new TacTranslator(parsed).translate().functions().get(0);
  }

  /** The type of the THROW in the function, or {@code null} when there is no throw. */
  private static String thrownTypeOf(TacFunction fn) {
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind == TacInstruction.Kind.THROW) {
        return ins.ctorType == null ? null : ins.ctorType.displayName();
      }
    }
    return null;
  }

  @Test
  @DisplayName("the source lane resolves the type of a thrown variable")
  void theSourceLaneResolvesTheTypeOfAThrownVariable() {
    TacFunction fn =
        translate(
            "class T {\n"
                + "  void m(java.io.IOException e) {\n"
                + "    throw e;\n"
                + "  }\n"
                + "}\n");
    assertThat(thrownTypeOf(fn))
        .as("the thrown value's type, resolved")
        .isEqualTo("java.io.IOException");
  }

  @Test
  @DisplayName("the source lane resolves the type of a thrown construction")
  void theSourceLaneResolvesTheTypeOfAThrownConstruction() {
    TacFunction fn =
        translate(
            "class T {\n"
                + "  void m() {\n"
                + "    throw new java.io.IOException(\"boom\");\n"
                + "  }\n"
                + "}\n");
    assertThat(thrownTypeOf(fn))
        .as("the constructed exception's type, resolved")
        .isEqualTo("java.io.IOException");
  }

  @Test
  @DisplayName("the source lane keeps the type of a user-defined exception")
  void theSourceLaneKeepsTheTypeOfAUserDefinedException() {
    TacFunction fn =
        translate(
            "class T {\n"
                + "  static class MyEx extends RuntimeException {}\n"
                + "  void m(MyEx e) {\n"
                + "    throw e;\n"
                + "  }\n"
                + "}\n");
    assertThat(thrownTypeOf(fn))
        .as("a class declared in the scanned source is not on the solver's classpath")
        .isNotNull()
        .contains("MyEx");
  }

  @Test
  @DisplayName("the bytecode lane carries the type of a thrown local")
  void theBytecodeLaneCarriesTheTypeOfAThrownLocal(@TempDir Path tmp) throws Exception {
    Path source = tmp.resolve("ThrowDemo.java");
    Files.writeString(
        source,
        "public class ThrowDemo {\n"
            + "  static void rethrow(java.io.IOException e) throws java.io.IOException {\n"
            + "    throw e;\n"
            + "  }\n"
            + "}\n");
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
            source.toString());
    assertThat(rc).as("the demo compiles").isEqualTo(0);
    List<TacProgram> programs = new BytecodeAnalyzer(tmp).analyze();
    TacProgram program =
        programs.stream()
            .filter(p -> "ThrowDemo".equals(p.typeName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("ThrowDemo was not analysed"));
    TacFunction fn =
        program.functions().stream()
            .filter(f -> "rethrow".equals(f.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("rethrow was not translated"));
    assertThat(thrownTypeOf(fn))
        .as("the local's declared type, read out of the class file")
        .isEqualTo("java.io.IOException");
  }
}

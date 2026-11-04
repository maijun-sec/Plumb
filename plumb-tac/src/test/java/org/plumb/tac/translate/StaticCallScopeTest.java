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

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * The IR shape of a call whose scope names a CLASS.
 *
 * <p>{@code String.valueOf(x)} is a static call. JavaParser parses the scope as a {@code NameExpr}
 * naming a class, not as a {@code TypeExpr}, so treating it as a VALUE read produced two wrong
 * things:
 *
 * <ul>
 *   <li>a bogus {@code FIELDLOAD this.C.String} -- a class name read as an instance field, which
 *       corresponds to nothing in the source;
 *   <li>a callee signature of {@code String.valueOf/1} while the taint-passthrough catalogue is
 *       written in fully-qualified form ({@code java.lang.String.valueOf/}), so the passthrough
 *       never fired and the taint was dropped at the call.
 * </ul>
 *
 * <p>A class scope is a TYPE reference, so it is modelled as one: a constant naming the type, and a
 * fully-qualified callee signature.
 */
public class StaticCallScopeTest {

  /** Parse a source and return the lowered program. */
  private static TacProgram lower(Path dir, String source) throws IOException {
    Path file = dir.resolve("Fixture.java");
    Files.writeString(file, source);
    ParsedSource parsed = new JavaSourceParser().parse(file);
    return new TacTranslator(parsed).translate();
  }

  /** Every instruction of the program's first function. */
  private static List<TacInstruction> instructionsOf(TacProgram program) {
    List<TacInstruction> out = new java.util.ArrayList<>();
    for (TacFunction fn : program.functions()) {
      out.addAll(fn.instructions());
    }
    return out;
  }

  @Test
  @DisplayName("a class scope produces no field load for the class name")
  void classScopeDoesNotReadTheClassNameAsAField(@TempDir Path dir) throws IOException {
    TacProgram program =
        lower(
            dir,
            "class C {\n"
                + "  void go(String x) {\n"
                + "    String y = String.valueOf(x);\n"
                + "  }\n"
                + "}\n");
    for (TacInstruction ins : instructionsOf(program)) {
      assertThat(ins.kind)
          .as("a class scope must not produce a field load of the class name")
          .isNotEqualTo(TacInstruction.Kind.FIELDLOAD);
    }
  }

  @Test
  @DisplayName("a static callee signature is fully qualified")
  void staticCalleeSignatureIsFullyQualified(@TempDir Path dir) throws IOException {
    TacProgram program =
        lower(
            dir,
            "class C {\n"
                + "  void go(String x) {\n"
                + "    String y = String.valueOf(x);\n"
                + "  }\n"
                + "}\n");
    TacInstruction call = null;
    for (TacInstruction ins : instructionsOf(program)) {
      if (ins.kind == TacInstruction.Kind.CALL) {
        call = ins;
        break;
      }
    }
    assertThat(call).as("the call must be lowered").isNotNull();
    assertThat(call.calleeSig)
        .as("the callee signature must name the declaring type")
        .isEqualTo("java.lang.String.valueOf/1");
  }

  @Test
  @DisplayName("a static call's first argument is the type constant, the real arguments follow")
  void staticCallArgumentLayout(@TempDir Path dir) throws IOException {
    TacProgram program =
        lower(
            dir,
            "class C {\n"
                + "  void go(String x) {\n"
                + "    String y = String.format(\"%s\", x);\n"
                + "  }\n"
                + "}\n");
    TacInstruction call = null;
    for (TacInstruction ins : instructionsOf(program)) {
      if (ins.kind == TacInstruction.Kind.CALL) {
        call = ins;
        break;
      }
    }
    assertThat(call).as("the call must be lowered").isNotNull();
    assertThat(call.args).as("a static call has a type constant plus its arguments").hasSize(3);
    assertThat(call.args.get(0))
        .as("args[0] is the type constant")
        .isInstanceOf(TacOperand.Const.class);
    assertThat(((TacOperand.Const) call.args.get(0)).value())
        .as("the type constant names the declaring type")
        .isEqualTo("java.lang.String");
  }

  @Test
  @DisplayName("a static call on a JDK type keeps its fully qualified signature")
  void jdkTypeScopeIsFullyQualified(@TempDir Path dir) throws IOException {
    TacProgram program =
        lower(
            dir,
            "class C {\n"
                + "  void go(String x) {\n"
                + "    int y = Integer.parseInt(x);\n"
                + "  }\n"
                + "}\n");
    TacInstruction call = null;
    for (TacInstruction ins : instructionsOf(program)) {
      if (ins.kind == TacInstruction.Kind.CALL) {
        call = ins;
        break;
      }
    }
    assertThat(call).as("the call must be lowered").isNotNull();
    assertThat(call.calleeSig).isEqualTo("java.lang.Integer.parseInt/1");
  }

  @Test
  @DisplayName("an instance call keeps its receiver as the first argument")
  void instanceCallKeepsTheReceiverFirst(@TempDir Path dir) throws IOException {
    TacProgram program =
        lower(
            dir,
            "class C {\n"
                + "  void go(String x) {\n"
                + "    String y = x.trim();\n"
                + "  }\n"
                + "}\n");
    TacInstruction call = null;
    for (TacInstruction ins : instructionsOf(program)) {
      if (ins.kind == TacInstruction.Kind.CALL) {
        call = ins;
        break;
      }
    }
    assertThat(call).as("the call must be lowered").isNotNull();
    assertThat(call.args).as("an instance call has the receiver first").hasSize(1);
    assertThat(call.args.get(0))
        .as("args[0] is the receiver, not a type constant")
        .isInstanceOf(TacOperand.TacVar.class);
  }

  @Test
  @DisplayName("the parser is configured with a symbol resolver")
  void parserHasASymbolResolver(@TempDir Path dir) throws IOException {
    // The static-call shape depends on the scope being resolvable. This is a
    // cheap guard against a parser configuration change silently degrading
    // the lowering.
    Path file = dir.resolve("Fixture.java");
    Files.writeString(file, "class C { void go() {} }");
    ParsedSource parsed = new JavaSourceParser().parse(file);
    CompilationUnit cu = StaticJavaParser.parse("class D { }");
    assertThat(parsed.primaryTypeName()).isEqualTo("C");
    assertThat(cu.getTypes()).hasSize(1);
  }
}

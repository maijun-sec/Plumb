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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

/**
 * A field initializer is translated from a COPY of the declarator, and a copy that is not part of
 * the compilation unit cannot be resolved.
 *
 * <p><b>What was wrong.</b> The lowering routed a field's initializer through a detached copy of
 * the declarator, so the copy's parent chain stopped at the wrapper. JavaParser's symbol resolver
 * walks that chain to find the compilation unit, so {@code calculateResolvedType()} threw {@code
 * IllegalStateException: The node is not inserted in a CompilationUnit} for every call inside a
 * field initializer. A call that cannot be resolved has no return type, so a CHAINED call lost the
 * receiver's fully qualified name and was lowered with the placeholder owner {@code scope}: {@code
 * scope.exec/1} instead of {@code java.lang.Runtime.exec/1}. No sink family matches the
 * placeholder, so a sink inside a field initializer was never reported -- silently, with no drop.
 *
 * <p><b>How it was measured.</b> The same call in an initializer BLOCK kept its real owner and was
 * reported; the same call in a field INITIALIZER did not. The TAC itself showed the difference: the
 * block's call was {@code java.lang.Runtime.exec/1} and the field's was {@code scope.exec/1}.
 *
 * <p><b>What each test pins.</b>
 *
 * <ul>
 *   <li>an instance field initializer: the call keeps the receiver's fully qualified name;
 *   <li>a static field initializer: the same;
 *   <li>a field initializer inside a class that declares a constructor: the same;
 *   <li>the invariant: the field initializer and the initializer block agree on the callee's key.
 * </ul>
 */
class JavaFieldInitializerResolutionTest {

  /** The call the initializer makes: the marker of "this body was resolved". */
  private static final String RESOLVED = "java.lang.Runtime.exec/1";

  /** The same call, lowered with the placeholder owner because the receiver's type was unknown. */
  private static final String PLACEHOLDER = "scope.exec/1";

  private static TacProgram translate(String source) {
    ParsedSource parsed = new JavaSourceParser().parseFromString(Path.of("Init.java"), source);
    return new TacTranslator(parsed).translate();
  }

  /** The function with the given signature, or null. */
  private static TacFunction functionWithSignature(TacProgram program, String signature) {
    for (TacFunction fn : program.functions()) {
      if (fn.signature().equals(signature)) {
        return fn;
      }
    }
    return null;
  }

  /** The calls a function makes, as callee keys. */
  private static List<String> calleesOf(TacFunction fn) {
    return fn.instructions().stream()
        .filter(i -> i.kind == TacInstruction.Kind.CALL)
        .map(i -> i.calleeSig)
        .toList();
  }

  @Test
  @DisplayName("an instance field initializer keeps the callee's fully qualified name")
  void anInstanceFieldInitializerKeepsTheCalleesFullyQualifiedName() {
    TacProgram program =
        translate(
            "class C {\n"
                + "  Process a = Runtime.getRuntime().exec(System.getenv(\"PATH\"));\n"
                + "}\n");

    TacFunction init = functionWithSignature(program, "<init>()");
    assertThat(init).as("the class's instance initialisers must be in the program").isNotNull();
    assertThat(calleesOf(init))
        .as("the call must name its receiver, or no sink family matches it")
        .contains(RESOLVED)
        .doesNotContain(PLACEHOLDER);
  }

  @Test
  @DisplayName("a static field initializer keeps the callee's fully qualified name")
  void aStaticFieldInitializerKeepsTheCalleesFullyQualifiedName() {
    TacProgram program =
        translate(
            "class C {\n"
                + "  static Process a = Runtime.getRuntime().exec(System.getenv(\"PATH\"));\n"
                + "}\n");

    TacFunction clinit = functionWithSignature(program, "<clinit>()");
    assertThat(clinit).as("the class's static initialisers must be in the program").isNotNull();
    assertThat(calleesOf(clinit))
        .as("the call must name its receiver, or no sink family matches it")
        .contains(RESOLVED)
        .doesNotContain(PLACEHOLDER);
  }

  @Test
  @DisplayName("a field initializer in a class that declares a constructor keeps the callee's name")
  void aFieldInitializerInAClassWithAConstructorKeepsTheCalleesFullyQualifiedName() {
    TacProgram program =
        translate(
            "class C {\n"
                + "  Process a = Runtime.getRuntime().exec(System.getenv(\"PATH\"));\n"
                + "  C() {}\n"
                + "}\n");

    TacFunction constructor = functionWithSignature(program, "C()");
    assertThat(constructor).as("the constructor must be in the program").isNotNull();
    assertThat(calleesOf(constructor))
        .as("the field initializer runs inside the constructor, and must resolve there too")
        .contains(RESOLVED)
        .doesNotContain(PLACEHOLDER);
  }

  @Test
  @DisplayName("a field initializer and an initializer block agree on the callee's key")
  void aFieldInitializerAndAnInitializerBlockAgreeOnTheCalleesKey() {
    TacProgram program =
        translate(
            "class C {\n"
                + "  Process a = Runtime.getRuntime().exec(System.getenv(\"PATH\"));\n"
                + "  { Runtime.getRuntime().exec(System.getenv(\"PATH\")); }\n"
                + "}\n");

    TacFunction init = functionWithSignature(program, "<init>()");
    assertThat(init).as("the class's instance initialisers must be in the program").isNotNull();
    List<String> callees = calleesOf(init);
    List<String> execCalls = callees.stream().filter(c -> c.endsWith("exec/1")).toList();
    assertThat(execCalls)
        .as("both bodies are in the same function, so both must resolve the same way")
        .isNotEmpty()
        .containsOnly(RESOLVED);
    assertThat(callees)
        .as("no call in either body may fall back to the placeholder owner")
        .doesNotContain(PLACEHOLDER);
  }
}

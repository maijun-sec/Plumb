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
package org.plumb.tac.frontend.arkts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;

/**
 * The ArkTS frontend: a declaration inside a function is a function of its own.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>A function declared inside a function body is a function of its own, and the sidecar lifts it
 * out of the function it is written in. It used to lose it on the way: the lifted functions were
 * pushed into the list of pending functions AFTER that list had already been replaced, so the push
 * landed in an array nobody read any more. The function was in the source, its body was in the
 * source, and the program had neither -- and nothing said so.
 *
 * <p>The second case is the other end of the same trade. A class whose body holds a nested class is
 * a class the COMPILER loses: the syntax tree it hands over stops at the nested class, and the
 * members that come after it are not in the class at all. The sidecar cannot restore what the
 * compiler did not give it, but it can say so: the class's own function carries the problem, the
 * file's precision drops, and the reader is told that this class was not analysed. A silent loss and
 * a reported loss are very different things -- the first is a finding that is not there, the second
 * is a finding about the analysis itself.
 *
 * <p>The assertions are about the SHAPE: the nested function is a function of the program, with a
 * name of its own and the signature that names it.
 *
 * <h2>Why it SKIPS</h2>
 *
 * <p>The sidecar needs Node and the compiler it imports, which npm installs. A machine without them
 * is missing an environment, not carrying a broken product -- the same reason the Go front end's
 * test skips when there is no Go toolchain. The message names the command that fixes it, so a skip
 * is a to-do and not a shrug.
 */
@DisplayName("ArkTS: a declaration inside a function is a function of its own")
class ArktsNestedDeclarationTest {

  /** A function declared inside a function body. */
  private static final String NESTED_IN_FUNCTION =
      """
      export function outer(cmd: string): void {
        function inner(value: string): string {
          return value + cmd;
        }
        const out = inner("x");
      }
      """;

  /** A class declared inside a function body. */
  private static final String NESTED_CLASS_IN_FUNCTION =
      """
      export function outer(cmd: string): void {
        class Inner {
          go(): void {
            const value = 1;
          }
        }
        const made = new Inner();
      }
      """;

  /** A class whose body holds a nested class. */
  private static final String NESTED_IN_BODY =
      """
      export class Holder {
        class BodyInner {
          go(): void {
            const value = 1;
          }
        }
      }
      """;

  /** A class whose body holds a nested class and declares its own constructor. */
  private static final String NESTED_WITH_CONSTRUCTOR =
      """
      export class Holder {
        constructor() {
          const value = 1;
        }
        class BodyInner {
          go(): void {
            const value = 2;
          }
        }
      }
      """;

  /** The same class without a nested class: the control. */
  private static final String WITHOUT_NESTED =
      """
      export class Control {
        plain(): void {
          const value = 1;
        }
      }
      """;

  @Test
  @DisplayName("a function declared inside a function is in the program")
  void aFunctionDeclaredInsideAFunctionIsInTheProgram(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = functionsOf(NESTED_IN_FUNCTION, directory);

    assertThat(functions)
        .as(
            "a function declared inside a function body is a function of its own -- a function that"
                + " is lifted out of the function it is written in must not be lifted out of the"
                + " program as well")
        .anyMatch(function -> "inner".equals(function.name()));
  }

  @Test
  @DisplayName("the nested function's signature names it")
  void theNestedFunctionsSignatureNamesIt(@TempDir Path directory) throws IOException {
    TacFunction nested = null;
    for (TacFunction function : functionsOf(NESTED_IN_FUNCTION, directory)) {
      if ("inner".equals(function.name())) {
        nested = function;
        break;
      }
    }

    assertThat(nested).as("the nested function must be in the program").isNotNull();
    assertThat(nested.signature())
        .as("the signature is what the function is called by, so it has to name the nested function")
        .contains("inner(");
  }

  @Test
  @DisplayName("a class declared inside a function is in the program")
  void aClassDeclaredInsideAFunctionIsInTheProgram(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = functionsOf(NESTED_CLASS_IN_FUNCTION, directory);

    assertThat(functions)
        .as(
            "a class declared inside a function body is a class of its own, and its methods are"
                + " functions of the program -- a class that is lifted out of the function must not"
                + " be lifted out of the program as well")
        .anyMatch(function -> "go".equals(function.name()) && function.enclosingClass() != null
            && function.enclosingClass().endsWith("Inner"));
  }

  @Test
  @DisplayName("a class whose body holds a nested class is reported as not analysed")
  void aClassWhoseBodyHoldsANestedClassIsReportedAsNotAnalysed(@TempDir Path directory)
      throws IOException {
    TacFrontend.ParseResult result = parse(NESTED_IN_BODY, directory);

    assertThat(result.precision())
        .as(
            "the compiler's syntax tree does not contain this class's members, so the class was not"
                + " analysed whole -- and the analysis has to say so, because a loss that is not"
                + " reported is a finding that is not there")
        .isNotEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("the nested class is still a class of its own")
  void theNestedClassIsStillAClassOfItsOwn(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = functionsOf(NESTED_IN_BODY, directory);

    assertThat(functions)
        .as(
            "the compiler lifts the nested class out of the class that held it, but the nested class"
                + " itself is whole -- its own members are functions of the program")
        .anyMatch(function -> "go".equals(function.name()));
  }

  @Test
  @DisplayName("a class with a constructor whose body holds a nested class is reported as not analysed")
  void aClassWithAConstructorWhoseBodyHoldsANestedClassIsReportedAsNotAnalysed(
      @TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = parse(NESTED_WITH_CONSTRUCTOR, directory);

    assertThat(result.precision())
        .as(
            "a class that declares its own constructor has THAT constructor as its own function --"
                + " and the loss has to be carried by whichever function is the class's own, or the"
                + " class would be reported whole while half of it was never analysed")
        .isNotEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("a class without a nested class is analysed whole")
  void aClassWithoutANestedClassIsAnalysedWhole(@TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = parse(WITHOUT_NESTED, directory);

    assertThat(result.precision())
        .as(
            "the control: a class whose body holds no nested class is in the tree whole, so its file"
                + " keeps its full precision -- without this, the case above would pass for the wrong"
                + " reason")
        .isEqualTo(IrPrecision.SOURCE_FULL);
  }

  // ---- helpers -------------------------------------------------------------------------------

  /** The functions of the source, translated through the real sidecar. */
  private static List<TacFunction> functionsOf(String source, Path directory) throws IOException {
    return parse(source, directory).functions();
  }

  /** The parse of the source, through the real sidecar. */
  private static TacFrontend.ParseResult parse(String source, Path directory) throws IOException {
    Path file = directory.resolve("Page.ets");
    Files.writeString(file, source);

    try (ArktsTacFrontend frontend = new ArktsTacFrontend(SourceLanguage.ARKTS)) {
      assumeTrue(
          sidecarAvailable(),
          "the ArkTS front end needs Node and the sidecar's own compiler dependency: run npm ci in"
              + " plumb-frontend/arkts-sidecar");

      return frontend.parse(source, file, null);
    }
  }

  /**
   * Whether the sidecar can run here.
   *
   * <p>Three things have to be there: Node, the sidecar's script, and the compiler the script
   * imports. The third is the one that is easy to miss, and its absence would make the sidecar die
   * on its first request -- which would look like a broken product rather than a missing
   * dependency.
   */
  private static boolean sidecarAvailable() {
    if (ArktsSidecarClient.locateNode() == null) {
      return false;
    }
    Path script = ArktsSidecarClient.locateScript(null);
    if (script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null && Files.isDirectory(root.resolve("node_modules").resolve("ohos-typescript"));
  }
}

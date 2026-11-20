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
import org.plumb.tac.ir.TacDrop;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The ArkTS front end: the members of an object literal.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>A method in an object literal is a member of its own, and the side car used to lose it. The
 * side car read every member's value from {@code property.initializer}, and a method has no
 * initialiser: it has a body. So the member was written into the object with no value at all, the
 * body was never lowered, and every sink inside it was gone -- with no drop and no problem to say
 * so. A sink that is not a call cannot be matched by any rule, so this was a silent miss: the file
 * reported full precision and the code was simply not there.
 *
 * <p>Now the body is lowered into a function of its own, the way the body of a class's method is,
 * and a sink in it is a sink again. What is NOT modelled is the capture: a lifted body has a scope
 * of its own, so the names it read from the scope around it are not in it, and the value the
 * literal stores for the member is unknown. Both are recorded.
 *
 * <p>The assertions are about SHAPES: there is a function that holds the body, the sink is a call
 * inside it, the call names the method, the loss is recorded, and two literals that each have a
 * method of the same name are two functions.
 */
@DisplayName("ArkTS: the members of an object literal")
class ArktsObjectLiteralMemberTest {

  /** An object literal with a method, a getter, a setter and a call of the method. */
  private static final String LITERAL =
      """
      export function build(cmd: string): any {
        const handler = {
          run(input: string): void {
            eval(input);
          },
          get value(): string {
            return cmd;
          },
          set value(next: string) {
            cmd = next;
          },
        };
        handler.run(cmd);
        return handler;
      }
      """;

  /** An object literal with a shorthand property. */
  private static final String SHORTHAND =
      """
      export function build(cmd: string, other: string): any {
        const handler = {
          plain: cmd,
          other,
        };
        return handler;
      }
      """;

  /** Two object literals that each have a method of the same name. */
  private static final String TWO_LITERALS =
      """
      export function build(cmd: string): any {
        const first = {
          run(input: string): void {
            eval(input);
          },
        };
        const second = {
          run(input: string): void {
            eval(input);
          },
        };
        return [first, second];
      }
      """;

  @Test
  @DisplayName("the method of an object literal is a function of its own")
  void theMethodOfAnObjectLiteralIsAFunctionOfItsOwn(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(LITERAL, directory, "Page.ets");

    assertThat(functions)
        .as(
            "the body of a method has a scope of its own, so it cannot live in the stream of the"
                + " function around it -- it has to be a function of its own, or the code inside it"
                + " is not in the TAC at all")
        .anyMatch(function -> "run".equals(function.name()));
  }

  @Test
  @DisplayName("the sink inside the method is a call")
  void theSinkInsideTheMethodIsACall(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(LITERAL, directory, "Page.ets");
    TacFunction method = methodNamed(functions, "run");

    assertThat(method.instructions().stream().map(instruction -> instruction.calleeSig))
        .as(
            "the eval inside the method has to be a CALL: a sink that is not a call cannot be"
                + " matched by any rule, so a body that is not lowered is a silent miss")
        .anyMatch(signature -> signature != null && signature.startsWith("eval.eval/"));
  }

  @Test
  @DisplayName("the call of the method names the method")
  void theCallOfTheMethodNamesTheMethod(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(LITERAL, directory, "Page.ets");
    TacFunction method = methodNamed(functions, "run");
    TacInstruction call =
        functions.stream()
            .flatMap(function -> function.instructions().stream())
            .filter(instruction -> instruction.kind == TacInstruction.Kind.CALL)
            .filter(instruction -> instruction.calleeSig != null)
            .filter(instruction -> instruction.calleeSig.contains("run/"))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("the call of the method is not in the stream at all"));

    assertThat(methodOf(call.calleeSig))
        .as(
            "the engine matches a call to the function it names by the METHOD's name: a call of a"
                + " property has no owner it could name, and the function it reaches has one -- the"
                + " two have to agree on the name, or the call resolves to a placeholder and the"
                + " body it names is never reached")
        .isEqualTo(methodOf(method.signature()));
  }

  @Test
  @DisplayName("the capture the lifting cannot model is recorded")
  void theCaptureTheLiftingCannotModelIsRecorded(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(LITERAL, directory, "Page.ets");
    TacFunction outer =
        functions.stream()
            .filter(function -> "build".equals(function.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the function build is not in the stream"));

    assertThat(outer.drops())
        .as("the names the body read from the scope around it are not in the lifted function")
        .extracting(TacDrop::construct)
        .contains("object-literal-method");
    assertThat(outer.irPrecision())
        .as("a recorded loss must not be reported as full precision")
        .isNotEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("two object literals with a method of the same name are two functions")
  void twoObjectLiteralsWithAMethodOfTheSameNameAreTwoFunctions(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = translateAll(TWO_LITERALS, directory, "Page.ets");
    List<TacFunction> methods =
        functions.stream().filter(function -> "run".equals(function.name())).toList();

    assertThat(methods)
        .as(
            "the function table is keyed by the signature, and the signature of a member of an"
                + " object literal has to say WHICH literal it belongs to -- two literals that each"
                + " have a `run` would otherwise share one signature, and the second one's body"
                + " would replace the first one's")
        .hasSize(2);
    assertThat(methods.stream().map(TacFunction::signature).distinct())
        .as(
            "the two signatures have to be DIFFERENT: the function table is keyed by the signature,"
                + " and two members with the same one are one member as far as the engine is"
                + " concerned -- the second body would replace the first one")
        .hasSize(2);
  }

  @Test
  @DisplayName("a shorthand property holds the value of its name")
  void aShorthandPropertyHoldsTheValueOfItsName(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(SHORTHAND, directory, "Page.ets");
    TacFunction outer =
        functions.stream()
            .filter(function -> "build".equals(function.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the function build is not in the stream"));

    assertThat(valueStoredUnder(outer, "other"))
        .as(
            "`{ other }` stands for the value of `other`: the name is a shorthand for the property"
                + " AND for the variable of the same name -- so the property holds that variable"
                + " and not an unknown value")
        .isEqualTo("other");
    assertThat(valueStoredUnder(outer, "plain"))
        .as("a property with an initialiser holds the value of that initialiser")
        .isEqualTo("cmd");
  }

  @Test
  @DisplayName("the body of the method is not in the function that holds the literal")
  void theBodyOfTheMethodIsNotInTheFunctionThatHoldsTheLiteral(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = translateAll(LITERAL, directory, "Page.ets");
    TacFunction outer =
        functions.stream()
            .filter(function -> "build".equals(function.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the function build is not in the stream"));

    assertThat(outer.instructions().stream().map(instruction -> instruction.calleeSig))
        .as(
            "the body was moved into a function of its own, so the function that holds the literal"
                + " must NOT have the body's code in it as well -- otherwise the sink would be"
                + " counted twice, and the lift would not be a lift")
        .noneMatch(signature -> signature != null && signature.startsWith("eval.eval/"));
  }

  // ---- the harness ---------------------------------------------------------------------------

  /** Every function the front end produced, translated through the real sidecar. */
  private static List<TacFunction> translateAll(String source, Path directory, String fileName)
      throws IOException {
    Path file = directory.resolve(fileName);
    Files.writeString(file, source);

    try (ArktsTacFrontend frontend = new ArktsTacFrontend(SourceLanguage.ARKTS)) {
      assumeTrue(
          sidecarAvailable(),
          "the ArkTS front end needs Node and the sidecar's own compiler dependency: run npm ci in"
              + " plumb-frontend/arkts-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      return result.functions();
    }
  }

  /** The function of the given name, or a failure that says what is missing. */
  private static TacFunction methodNamed(List<TacFunction> functions, String name) {
    return functions.stream()
        .filter(function -> name.equals(function.name()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "there is no function named "
                        + name
                        + ": the body of the member of the object"
                        + " literal is not in the TAC at all, so the code inside it is gone"));
  }

  /**
   * The name of the variable an object literal stored under a key.
   *
   * <p>The write is found by its KEY, not by its position: the position would make the assertion a
   * statement about the layout of the file rather than about the shape of the TAC.
   *
   * @param function the function that holds the literal
   * @param key the name of the property
   * @return the name of the variable the property holds, or null when there is no such write
   */
  private static String valueStoredUnder(TacFunction function, String key) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind != TacInstruction.Kind.ARRAYSTORE) {
        continue;
      }
      if (!(instruction.lhs2 instanceof TacOperand.Const constant)) {
        continue;
      }
      if (constant.kind() != TacOperand.Const.Kind.STRING) {
        continue;
      }
      if (!key.equals(constant.value())) {
        continue;
      }
      return instruction.dest instanceof TacOperand.TacVar variable ? variable.name() : null;
    }
    return null;
  }

  /**
   * The method a call key or a signature names, without its owner.
   *
   * <p>The owner is only a spelling: a call of a property has no owner it could name, and the
   * function it reaches has one. The engine matches the two on the method's name.
   *
   * @param key the call key or the signature
   * @return the name of the method, or null when the key names none
   */
  private static String methodOf(String key) {
    if (key == null) {
      return null;
    }
    int end = key.indexOf('(');
    if (end < 0) {
      end = key.indexOf('/');
    }
    if (end < 0) {
      end = key.length();
    }
    String head = key.substring(0, end);
    int dot = head.lastIndexOf('.');
    return dot >= 0 ? head.substring(dot + 1) : head;
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
    return root != null
        && Files.isDirectory(root.resolve("node_modules").resolve("ohos-typescript"));
  }
}

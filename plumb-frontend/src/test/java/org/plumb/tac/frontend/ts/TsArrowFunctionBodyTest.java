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
package org.plumb.tac.frontend.ts;

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

/**
 * The TypeScript frontend: the body of an arrow function, and the module's own exports.
 *
 * <h2>Why the body of an arrow function needs its own test</h2>
 *
 * <p>A function's body is usually a BLOCK. An arrow function may have a single EXPRESSION instead
 * -- {@code () => value} -- and that expression is code, and it is what the function returns.
 *
 * <p>The body was lowered as if it were always a block, and a block has {@code statements} while an
 * expression does not: the walk ran over {@code undefined} and threw. The throw ended the whole
 * body. The function was in the TAC, it reported full precision, and the code inside it -- a sink
 * with a tainted argument, say -- was simply not there.
 *
 * <p>So the body is lowered as what it is: an expression, and a return of that expression.
 *
 * <h2>Why the lifting is recorded where it is</h2>
 *
 * <p>Lifting a function body into a function of its own gives that body a scope of its own, and
 * the names it read from the scope around it are not in it. That loss belongs to the LIFTED
 * function -- it is the one whose TAC cannot see those names -- and the loss is recorded on it, so
 * that its precision says so.
 *
 * <p>The function AROUND it keeps its precision: the body does not run where the declaration is,
 * so nothing the body does reaches that scope. A drop on the wrong function would make the wrong
 * function look degraded, and would hide the fact that the right one is.
 *
 * <h2>Why the export needs its own test</h2>
 *
 * <p>An export is a statement the module has. Two of the shapes were asked about with two helpers
 * that were USED but never IMPORTED, so asking the question threw a ReferenceError, the module's
 * own stream was abandoned, and the file reported a loss it did not have.
 *
 * <p>The assertions are about SHAPES: the body is in the TAC, the loss is recorded on the function
 * that has it, and the module keeps its code. They do not pin where the instructions are.
 */
@DisplayName("TypeScript: the body of an arrow function, and the module's exports")
class TsArrowFunctionBodyTest {

  /** An arrow function whose body is a single expression, and that expression reaches a sink. */
  private static final String CONCISE_BODY =
      """
      export function outer(request: any): any {
        const g = () => eval(request["cmd"]);
        return g;
      }
      """;

  /** A module that exports: one export of a declaration, and one export of an assignment. */
  private static final String EXPORTS =
      """
      function helper(value: any): any {
        return value;
      }

      export { helper };
      export default helper;
      """;

  @Test
  @DisplayName("the concise body of an arrow function keeps its code")
  void theConciseBodyOfAnArrowFunctionKeepsItsCode(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(CONCISE_BODY, directory, "app.ts");

    TacFunction body = theFunctionHoldingTheEval(functions);
    assertThat(body.instructions().stream().map(instruction -> instruction.calleeSig))
        .as("the body keeps its own code: the eval inside it has to be in the stream")
        .anyMatch(signature -> signature != null && signature.startsWith("eval.eval/"));
  }

  @Test
  @DisplayName("the concise body is what the arrow function returns")
  void theConciseBodyIsWhatTheArrowFunctionReturns(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(CONCISE_BODY, directory, "app.ts");

    TacFunction body = theFunctionHoldingTheEval(functions);
    assertThat(body.instructions())
        .as("a concise body is an expression, and the function returns it")
        .anyMatch(instruction -> instruction.kind == TacInstruction.Kind.RETURN);
  }

  @Test
  @DisplayName("the capture the lifting cannot model is recorded on the lifted function")
  void theCaptureTheLiftingCannotModelIsRecordedOnTheLiftedFunction(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = translateAll(CONCISE_BODY, directory, "app.ts");

    TacFunction body = theFunctionHoldingTheEval(functions);
    assertThat(body.drops())
        .as("the names the body read from the scope around it are not in the lifted function")
        .extracting(TacDrop::construct)
        .contains("lifted-function");
    assertThat(body.irPrecision())
        .as("a recorded loss must not be reported as full precision")
        .isNotEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("the function around the lifting keeps its precision")
  void theFunctionAroundTheLiftingKeepsItsPrecision(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(CONCISE_BODY, directory, "app.ts");

    TacFunction outer =
        functions.stream()
            .filter(function -> "outer".equals(function.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the function outer is not in the stream"));

    assertThat(outer.irPrecision())
        .as(
            "the body does not run where the declaration is, so what it does cannot reach this"
                + " scope: the loss belongs to the lifted function, not to this one")
        .isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("a module that exports is lowered whole")
  void aModuleThatExportsIsLoweredWhole(@TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = translate(EXPORTS, directory, "app.ts");

    assertThat(result.problems())
        .as("an export of a declaration and an export of an assignment must both be understood")
        .isEmpty();
    assertThat(result.functions())
        .as("the helper the module exports is in the stream")
        .anyMatch(function -> "helper".equals(function.name()));
    assertThat(result.functions())
        .as("nothing in the file was lost")
        .allSatisfy(function -> assertThat(function.irPrecision()).isEqualTo(IrPrecision.SOURCE_FULL));
  }

  // ---- the harness ---------------------------------------------------------------------------

  /** The function that holds the eval call: the one the body was lifted into. */
  private static TacFunction theFunctionHoldingTheEval(List<TacFunction> functions) {
    return functions.stream()
        .filter(
            function ->
                function.instructions().stream()
                    .anyMatch(
                        instruction ->
                            instruction.calleeSig != null
                                && instruction.calleeSig.startsWith("eval.eval/")))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "no function holds the eval call: the body of the arrow function is not in"
                        + " the TAC at all, so the code inside it is gone"));
  }

  /** Parse one file through the real sidecar. */
  private static TacFrontend.ParseResult translate(String source, Path directory, String fileName)
      throws IOException {
    Path file = directory.resolve(fileName);
    Files.writeString(file, source);

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      return frontend.parse(source, file, null);
    }
  }

  /** Every function the front end produced, translated through the real sidecar. */
  private static List<TacFunction> translateAll(String source, Path directory, String fileName)
      throws IOException {
    return translate(source, directory, fileName).functions();
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
    if (TsSidecarClient.locateNode() == null) {
      return false;
    }
    Path script = TsSidecarClient.locateScript(null);
    if (script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null && Files.isDirectory(root.resolve("node_modules").resolve("typescript"));
  }
}

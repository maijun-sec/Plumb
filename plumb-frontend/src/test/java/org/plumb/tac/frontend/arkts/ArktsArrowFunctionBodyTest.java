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

/**
 * The ArkTS frontend: the body of an arrow function.
 *
 * <h2>Why this test exists next to the TypeScript one</h2>
 *
 * <p>The two sidecars are separate programs, and a fix in one of them is not a fix in the other:
 * the TypeScript sidecar was missing an import that the ArkTS one has, and the ArkTS sidecar has
 * its own lowering of the same constructs. A test that only walked one of them would leave the
 * other free to keep the defect, and the defect is silent -- the body is gone, the function still
 * reports full precision, and the sink inside it is never reached.
 *
 * <h2>What the body is, and what it is not</h2>
 *
 * <p>A function's body is usually a BLOCK. An arrow function may have a single EXPRESSION instead
 * -- {@code () => value} -- and that expression is code, and it is what the function returns.
 * Lowered as if it were a block, the walk ran over {@code undefined} and threw, and the throw ended
 * the whole body.
 *
 * <p>Lifting that body into a function of its own gives it a scope of its own, and the names it
 * read from the scope around it are not in it. That loss belongs to the LIFTED function -- it is
 * the one whose TAC cannot see those names -- and it is recorded there. The function around it
 * keeps its precision, because the body does not run where the declaration is.
 *
 * <p>The assertions are about SHAPES: the body is in the TAC, the loss is recorded on the function
 * that has it, and the function around it is unaffected. They do not pin where the instructions
 * are.
 */
@DisplayName("ArkTS: the body of an arrow function")
class ArktsArrowFunctionBodyTest {

  /** An arrow function whose body is a single expression, and that expression reaches a sink. */
  private static final String CONCISE_BODY =
      """
      export function outer(request: any): any {
        const g = () => eval(request["cmd"]);
        return g;
      }
      """;

  @Test
  @DisplayName("the concise body of an arrow function keeps its code")
  void theConciseBodyOfAnArrowFunctionKeepsItsCode(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(CONCISE_BODY, directory, "app.ets");

    TacFunction body = theFunctionHoldingTheEval(functions);
    assertThat(body.instructions().stream().map(instruction -> instruction.calleeSig))
        .as("the body keeps its own code: the eval inside it has to be in the stream")
        .anyMatch(signature -> signature != null && signature.startsWith("eval.eval/"));
  }

  @Test
  @DisplayName("the concise body is what the arrow function returns")
  void theConciseBodyIsWhatTheArrowFunctionReturns(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(CONCISE_BODY, directory, "app.ets");

    TacFunction body = theFunctionHoldingTheEval(functions);
    assertThat(body.instructions())
        .as("a concise body is an expression, and the function returns it")
        .anyMatch(instruction -> instruction.kind == TacInstruction.Kind.RETURN);
  }

  @Test
  @DisplayName("the by-reference loss is recorded on the lifted function")
  void theByReferenceLossIsRecordedOnTheLiftedFunction(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(CONCISE_BODY, directory, "app.ets");

    TacFunction body = theFunctionHoldingTheEval(functions);
    assertThat(body.drops())
        .as(
            "the source shares one binding between the scope and the closure, and the TAC hands"
                + " the captured value over by copy")
        .extracting(TacDrop::construct)
        .contains("closure-capture");
    assertThat(body.irPrecision())
        .as("a recorded loss must not be reported as full precision")
        .isNotEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("the function around the lifting keeps its precision")
  void theFunctionAroundTheLiftingKeepsItsPrecision(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(CONCISE_BODY, directory, "app.ets");

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

  /**
   * Whether the sidecar can run here.
   *
   * <p>Three things have to be there: Node, the sidecar's script, and the compiler the script
   * imports -- which for this sidecar is {@code ohos-typescript}, not {@code typescript}. The third
   * is the one that is easy to miss, and its absence would make the sidecar die on its first
   * request -- which would look like a broken product rather than a missing dependency.
   *
   * <p>A skip here is not a pass: it means the ArkTS front end was not exercised at all, and the
   * build stays green either way.
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

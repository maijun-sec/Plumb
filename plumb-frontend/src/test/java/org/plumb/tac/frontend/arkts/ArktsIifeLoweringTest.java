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
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacDrop;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * The ArkTS frontend: the body of an immediately invoked function, and the owner of a global.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Two separate losses, found by probing the side car rather than by reading it.
 *
 * <p>The first is the IIFE. {@code (() => { ... })()} calls a function literal, and the literal's
 * body is code. Before this was handled, that body was not in the TAC at all: not as instructions,
 * and not as a drop either. The function around it reported full precision, the scan reported
 * success, and the code was simply gone -- the worst combination of all, because nothing anywhere
 * says that anything was lost.
 *
 * <p>The second is the owner of a free global function. A call whose receiver nobody could type has
 * no owner, and the catalogue's patterns are anchored at the owner: the global {@code eval} is
 * claimed as {@code eval.eval/1} and not as {@code eval/1}. ArkTS's table of the globals the
 * language provides did not name {@code eval}, so its {@code eval} call arrived with no owner and
 * no rule could claim it -- a code injection sink that never fires.
 *
 * <p>The assertions are about SHAPES: there is a function that holds the body, the call names it,
 * the loss is recorded, and the global's key carries the global's own name. They do not pin where
 * the instructions are.
 */
@DisplayName("ArkTS: the body of an immediately invoked function, and the owner of a global")
class ArktsIifeLoweringTest {

  /** An IIFE whose body reaches a sink. */
  private static final String IIFE =
      """
      export function runIife(request: any): any {
        (() => {
          eval(request["cmd"]);
        })();
        return request;
      }
      """;

  /** A free global function called with nothing to name a receiver. */
  private static final String FREE_GLOBAL =
      """
      export function runGlobal(input: string): void {
        eval(input);
      }
      """;

  @Test
  @DisplayName("the body of an IIFE is a function of its own")
  void theBodyOfAnIifeIsAFunctionOfItsOwn(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(IIFE, directory, "app.ets");

    TacFunction body =
        functions.stream()
            .filter(function -> !"runIife".equals(function.name()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the IIFE's body is not a function of its own: it is not in the TAC at all,"
                            + " so the code inside it is gone"));

    assertThat(body.instructions().stream().map(instruction -> instruction.calleeSig))
        .as("the body keeps its own code: the eval inside it has to be in the stream")
        .anyMatch(signature -> signature != null && signature.startsWith("eval.eval/"));
  }

  @Test
  @DisplayName("the call names the lifted function")
  void theCallNamesTheLiftedFunction(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(IIFE, directory, "app.ets");
    TacFunction outer =
        functions.stream()
            .filter(function -> "runIife".equals(function.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the function runIife is not in the stream"));

    TacInstruction call =
        outer.instructions().stream()
            .filter(instruction -> instruction.kind == TacInstruction.Kind.CALL)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the IIFE's call is not in the stream"));

    assertThat(call.calleeSig)
        .as("the call names the function the body was lifted into, so the engine can resolve it")
        .contains("closure$");
  }

  @Test
  @DisplayName("the capture the lifting cannot model is recorded")
  void theCaptureTheLiftingCannotModelIsRecorded(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(IIFE, directory, "app.ets");
    TacFunction outer =
        functions.stream()
            .filter(function -> "runIife".equals(function.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the function runIife is not in the stream"));

    assertThat(outer.drops())
        .as("the names the body read from the scope around it are not in the lifted function")
        .extracting(TacDrop::construct)
        .contains("iife-body");
  }

  @Test
  @DisplayName("a free global function carries the global's own name as its owner")
  void aFreeGlobalFunctionCarriesTheGlobalsOwnNameAsItsOwner(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = translateAll(FREE_GLOBAL, directory, "global.ets");

    TacFunction global =
        functions.stream()
            .filter(function -> "runGlobal".equals(function.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the function runGlobal is not in the stream"));

    assertThat(global.instructions().stream().map(instruction -> instruction.calleeSig))
        .as(
            "a call whose receiver nobody could type belongs to the global it came from, and the"
                + " catalogue's patterns are anchored at that owner")
        .contains("eval.eval/1");
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

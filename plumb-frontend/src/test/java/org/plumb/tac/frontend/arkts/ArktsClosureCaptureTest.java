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
 * The ArkTS frontend: the value a closure takes from the scope around it.
 *
 * <h2>Why the capture needs its own test</h2>
 *
 * <p>A closure body runs in a scope of its own, so the names it reads from the scope around it are
 * not in it. The body was lowered anyway -- a sink in it is still a sink -- but the captured value
 * never arrived, and the sink inside the closure saw an unbound name: <b>the finding was lost</b>,
 * while the scan reported success.
 *
 * <p>So the captured value is handed over the way the engine already binds a call's arguments to
 * the callee's parameters: it is the closure's <b>leading parameter</b>, and the call that reaches
 * the closure hands it over <b>before</b> the arguments the source wrote. The engine binds a
 * function's parameters to a call's arguments in order, so the leading parameter is the first
 * argument -- and the closure's arity counts it, or the two ends would not agree.
 *
 * <h2>Which names are captures</h2>
 *
 * <p>The compiler's own symbol says: a name the closure binds itself is its own, a name another
 * file declares is a module's import or the standard library's, and a function or a class is
 * reached by name rather than handed over. What is left is a variable of the scope around the
 * closure -- and that is what gets handed over.
 *
 * <p>The assertions are about SHAPES: which parameters the closure has, what the call hands over,
 * and what the arity says. They do not pin where the instructions are.
 */
@DisplayName("ArkTS: the value a closure takes from the scope around it")
class ArktsClosureCaptureTest {

  /** A closure that reads an outer value and takes an argument of its own. */
  private static final String CAPTURE =
      """
      export function report(request: any): any {
        const captured = request["name"];
        const helper = (value: string): void => {
          console.log(captured, value);
        };
        helper("literal");
        return captured;
      }
      """;

  /** A closure that declares the name itself, so the outer one is not a capture. */
  private static final String SHADOWED =
      """
      export function report(request: any): any {
        const captured = request["name"];
        const helper = (): void => {
          const captured = "local";
          console.log(captured);
        };
        helper();
        return captured;
      }
      """;

  /** A closure that reads a global, which no caller hands over. */
  private static final String GLOBAL =
      """
      export function report(request: any): any {
        const helper = (): void => {
          console.log("nothing from outside");
        };
        helper();
        return request;
      }
      """;

  /** An IIFE that reads an outer value. */
  private static final String IIFE =
      """
      export function report(request: any): any {
        const captured = request["name"];
        ((): void => {
          console.log(captured);
        })();
        return captured;
      }
      """;

  @Test
  @DisplayName("the captured value is a leading parameter of the closure")
  void theCapturedValueIsALeadingParameterOfTheClosure(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(CAPTURE, directory, "app.ets");

    assertThat(parameterNames(theClosure(functions, "helper")))
        .as(
            "the captured value comes first, because the engine binds a call's arguments to the"
                + " callee's parameters in order")
        .containsExactly("captured", "value");
  }

  @Test
  @DisplayName("the call hands the captured value over before the arguments")
  void theCallHandsTheCapturedValueOverBeforeTheArguments(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = translateAll(CAPTURE, directory, "app.ets");

    assertThat(operandNames(theCall(functions, "report")))
        .as(
            "the first operand is the receiver slot the TAC reserves, then the captured value, and"
                + " then the argument the source wrote")
        .containsExactly("<const>", "captured", "<const>");
  }

  @Test
  @DisplayName("the closure's arity counts the captured value")
  void theClosuresArityCountsTheCapturedValue(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(CAPTURE, directory, "app.ets");

    TacInstruction call = theCall(functions, "report");
    assertThat(call.calleeSig)
        .as("the call has to name the closure with the arity the closure itself has")
        .endsWith("helper/2");
    assertThat(theClosure(functions, "helper").params())
        .as("the closure declares the captured value and its own argument")
        .hasSize(2);
  }

  @Test
  @DisplayName("a name the closure declares itself is not a capture")
  void aNameTheClosureDeclaresItselfIsNotACapture(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(SHADOWED, directory, "app.ets");

    assertThat(parameterNames(theClosure(functions, "helper")))
        .as(
            "the closure declares the name itself, so the outer variable is not the one the body"
                + " reads and nothing has to be handed over")
        .isEmpty();
  }

  @Test
  @DisplayName("a global is not a capture")
  void aGlobalIsNotACapture(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(GLOBAL, directory, "app.ets");

    assertThat(parameterNames(theClosure(functions, "helper")))
        .as("the standard library is not this scope's, and no caller hands it over")
        .isEmpty();
  }

  @Test
  @DisplayName("an IIFE hands the captured value over too")
  void anIifeHandsTheCapturedValueOverToo(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(IIFE, directory, "app.ets");

    assertThat(operandNames(theCall(functions, "report")))
        .as("the IIFE's body is a function of its own, and it captures the outer value")
        .containsExactly("<const>", "captured");
  }

  @Test
  @DisplayName("the by-reference loss is recorded on the closure")
  void theByReferenceLossIsRecordedOnTheClosure(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(CAPTURE, directory, "app.ets");

    TacFunction closure = theClosure(functions, "helper");
    assertThat(closure.drops())
        .as(
            "the source shares one binding between the scope and the closure, and the TAC hands"
                + " the captured value over by copy")
        .extracting(TacDrop::construct)
        .contains("closure-capture");
    assertThat(closure.irPrecision())
        .as("a recorded loss must not be reported as full precision")
        .isNotEqualTo(IrPrecision.SOURCE_FULL);
  }

  // ---- the harness ---------------------------------------------------------------------------

  /** The closure the source declares, by the name its variable has. */
  private static TacFunction theClosure(List<TacFunction> functions, String name) {
    return functions.stream()
        .filter(function -> name.equals(function.name()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "the closure " + name + " is not in the stream: its body is gone"));
  }

  /** The one call the function makes. */
  private static TacInstruction theCall(List<TacFunction> functions, String name) {
    return functions.stream()
        .filter(function -> name.equals(function.name()))
        .flatMap(function -> function.instructions().stream())
        .filter(instruction -> instruction.kind == TacInstruction.Kind.CALL)
        .findFirst()
        .orElseThrow(() -> new AssertionError("the call in " + name + " is not in the stream"));
  }

  /** The names of a function's parameters. */
  private static List<String> parameterNames(TacFunction function) {
    return function.params().stream().map(TacOperand.TacVar::name).toList();
  }

  /** The names of a call's operands: a variable by its name, anything else as a placeholder. */
  private static List<String> operandNames(TacInstruction call) {
    return call.args.stream()
        .map(operand -> operand instanceof TacOperand.TacVar variable ? variable.name() : "<const>")
        .toList();
  }

  /** Every function the front end produced, translated through the real sidecar. */
  private static List<TacFunction> translateAll(String source, Path directory, String fileName)
      throws IOException {
    Path file = directory.resolve(fileName);
    Files.writeString(file, source);

    try (ArktsTacFrontend frontend = new ArktsTacFrontend(SourceLanguage.ARKTS)) {
      assumeTrue(
          sidecarAvailable(),
          "the ArkTS front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/arkts-sidecar");

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

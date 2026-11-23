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
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * The TypeScript frontend: a {@code yield}, the generator's suspension point.
 *
 * <h2>What a yield is</h2>
 *
 * <p>{@code function* g() { const x = yield f(); } } — the operand {@code f()} is evaluated in the
 * generator's own stream, the generator hands its value to whoever drives it, and the generator
 * waits until that driver calls {@code next()}. The expression's own value is whatever the driver
 * sends back.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The expression switch had no case for a yield, so the whole expression fell through to the
 * fallback: the operand was never visited at all. A sink under a yield was not merely missed — it
 * was never looked for — and the function reported only that it had been lowered partially, which
 * is a coarser instrument than the loss deserves.
 *
 * <h2>What is still not in the TAC</h2>
 *
 * <p>The generator's PROTOCOL: the state machine that says where the generator resumes, and the
 * driver that calls {@code next()}. So the values a generator produces do not reach a driver, and
 * the value a yield expression produces — what the driver sends back — is unknown. Both are
 * recorded, so the gap is visible rather than silent.
 *
 * <p>Each assertion here says ONE thing. That is not a style choice: a case that asserted two facts
 * could only be half refuted by a mutation, and the half that survived would read as "it holds".
 */
@DisplayName("TypeScript: a yield in a generator")
class TsGeneratorYieldLoweringTest {

  /** A generator whose yield hands a call to the driver. */
  private static final String PLAIN =
      """
      import { exec } from "child_process";

      export function* gen(cmd: string): Generator<string, void, unknown> {
        const first = yield exec(cmd);
        return first;
      }
      """;

  /** A generator whose yield DELEGATES to another generator. */
  private static final String DELEGATED =
      """
      import { exec } from "child_process";

      export function* source(cmd: string): Generator<string, void, unknown> {
        yield exec(cmd);
      }

      export function* gen(cmd: string): Generator<string, void, unknown> {
        yield* source(cmd);
      }
      """;

  /** A function with no yield at all: the control. */
  private static final String CONTROL =
      """
      import { exec } from "child_process";

      export function plain(cmd: string): string {
        return exec(cmd);
      }
      """;

  @Test
  @DisplayName("the operand of a yield is evaluated in the generator's own stream")
  void theOperandOfAYieldIsEvaluatedInTheGeneratorsOwnStream(@TempDir Path directory)
      throws IOException {
    TacFunction generator = theGenerator(translateAll(PLAIN, directory, "app.ts"));

    assertThat(calleeSignatures(generator))
        .as(
            "the operand is user code: a sink inside it is a sink inside the generator, and it"
                + " was never even looked for before")
        .anyMatch(signature -> signature.startsWith("child_process.exec/"));
  }

  @Test
  @DisplayName("a yield is a construct the front end understands, not an unhandled node")
  void aYieldIsAConstructTheFrontEndUnderstands(@TempDir Path directory) throws IOException {
    TacFrontend.StructuredParseResult result = translateStructured(PLAIN, directory, "app.ts");

    assertThat(result.problems())
        .as("the construct is understood now, so there is nothing to report")
        .extracting(TacProgram.FunctionProblem::kind)
        .doesNotContain("UNHANDLED_NODE");
  }

  @Test
  @DisplayName("the generator's protocol is recorded as a loss of semantics")
  void theGeneratorsProtocolIsRecordedAsALossOfSemantics(@TempDir Path directory)
      throws IOException {
    TacFunction generator = theGenerator(translateAll(PLAIN, directory, "app.ts"));

    assertThat(generator.drops())
        .as("the driver that would resume the generator is not in the TAC, and that is semantics")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("expr.generator_yield");
              assertThat(drop.impact()).isEqualTo(TacDrop.Impact.SEMANTICS);
            });
  }

  @Test
  @DisplayName("the value a yield produces is recorded as a loss of one value")
  void theValueAYieldProducesIsRecordedAsALossOfOneValue(@TempDir Path directory)
      throws IOException {
    TacFunction generator = theGenerator(translateAll(PLAIN, directory, "app.ts"));

    assertThat(generator.drops())
        .as(
            "the value of a yield expression is what the driver sends back into the generator,"
                + " and the TAC does not know it")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("expr.generator_yield.value");
              assertThat(drop.impact()).isEqualTo(TacDrop.Impact.VALUE);
            });
  }

  @Test
  @DisplayName("the generator is degraded, not lowered: its body was traversed whole")
  void theGeneratorIsDegradedNotLowered(@TempDir Path directory) throws IOException {
    TacFunction generator = theGenerator(translateAll(PLAIN, directory, "app.ts"));

    assertThat(generator.irPrecision())
        .as(
            "the body is in the TAC and only the protocol is missing, so the loss is a degraded"
                + " one -- a lowered precision would say the body itself was not understood")
        .isEqualTo(IrPrecision.SOURCE_DEGRADED);
  }

  @Test
  @DisplayName("the operand of a delegated yield is evaluated too")
  void theOperandOfADelegatedYieldIsEvaluatedToo(@TempDir Path directory) throws IOException {
    TacFunction generator = theGenerator(translateAll(DELEGATED, directory, "app.ts"));

    assertThat(calleeSignatures(generator))
        .as("a delegated yield hands over another generator, and that call is user code as well")
        .anyMatch(signature -> signature.contains("source/"));
  }

  @Test
  @DisplayName("a delegated yield records its own loss: the iteration protocol is missing")
  void aDelegatedYieldRecordsItsOwnLoss(@TempDir Path directory) throws IOException {
    TacFunction generator = theGenerator(translateAll(DELEGATED, directory, "app.ts"));

    assertThat(generator.drops())
        .as(
            "a delegated yield hands the values of another iterable to the driver, and the"
                + " iteration protocol that produces them is not in the TAC")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("expr.generator_yield.delegate");
              assertThat(drop.impact()).isEqualTo(TacDrop.Impact.SEMANTICS);
            });
  }

  @Test
  @DisplayName("the value a yield produces is unknown, not the operand's value")
  void theValueAYieldProducesIsUnknownNotTheOperandsValue(@TempDir Path directory)
      throws IOException {
    TacFunction generator = theGenerator(translateAll(PLAIN, directory, "app.ts"));

    TacInstruction binding =
        generator.instructions().stream()
            .filter(
                instruction ->
                    instruction.kind == TacInstruction.Kind.MOVE
                        && instruction.dest instanceof TacOperand.TacVar destination
                        && "first".equals(destination.name()))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("the value of the yield is not bound in the TAC at all"));

    assertThat(definingInstruction(generator, binding.lhs1))
        .as(
            "the value of a yield expression is what the driver sends back; handing back the"
                + " operand's value instead would say the driver sends the generator's own"
                + " output back into it")
        .isNotNull();
    assertThat(definingInstruction(generator, binding.lhs1).kind)
        .as("so the value the yield expression produces is an unknown one")
        .isEqualTo(TacInstruction.Kind.HAVOC);
  }

  @Test
  @DisplayName("a function with no yield is left alone")
  void aFunctionWithNoYieldIsLeftAlone(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(CONTROL, directory, "app.ts");

    TacFunction plain =
        functions.stream()
            .filter(function -> "plain".equals(function.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the function plain is not in the stream"));

    assertThat(plain.drops())
        .as("a function with no generator in it loses nothing of this kind")
        .extracting(TacDrop::construct)
        .doesNotContain("expr.generator_yield", "expr.generator_yield.value");
    assertThat(plain.irPrecision())
        .as("and it keeps the precision it always had")
        .isEqualTo(IrPrecision.SOURCE_FULL);
  }

  // ---- the harness ---------------------------------------------------------------------------

  /** The generator function of a translation. */
  private static TacFunction theGenerator(List<TacFunction> functions) {
    return functions.stream()
        .filter(function -> "gen".equals(function.name()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("the generator is not in the stream"));
  }

  /** The callee signatures of a function's calls, in source order. */
  private static List<String> calleeSignatures(TacFunction function) {
    return function.instructions().stream()
        .filter(instruction -> instruction.kind == TacInstruction.Kind.CALL)
        .map(instruction -> instruction.calleeSig)
        .filter(signature -> signature != null)
        .toList();
  }

  /** The instruction that defines the given operand, when it is a variable. */
  private static TacInstruction definingInstruction(TacFunction function, TacOperand operand) {
    if (!(operand instanceof TacOperand.TacVar variable)) {
      return null;
    }
    return function.instructions().stream()
        .filter(
            instruction ->
                instruction.dest instanceof TacOperand.TacVar destination
                    && destination.name().equals(variable.name()))
        .findFirst()
        .orElse(null);
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
   * Parse one file through the real sidecar, in the STRUCTURED form.
   *
   * <p>The structured form is where a FUNCTION's problems live. The plain form carries the file's
   * own problems -- the ones about the file rather than about a construct inside it -- so a
   * function that reports "this construct has no lowering" does not show up there.
   */
  private static TacFrontend.StructuredParseResult translateStructured(
      String source, Path directory, String fileName) throws IOException {
    Path file = directory.resolve(fileName);
    Files.writeString(file, source);

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      return frontend.parseStructured(source, file, null);
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

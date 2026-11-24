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
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacResumePoint;

/**
 * The ArkTS frontend: a generator as the state machine it is.
 *
 * <h2>Why this test exists next to the TypeScript one</h2>
 *
 * <p>The two sidecars are separate programs, and a fix in one of them is not a fix in the other.
 * The TypeScript sidecar lowered a {@code yield}; the ArkTS one had no case for it at all, so the
 * operand was never visited -- the sink under a yield was not missed, it was never looked for.
 *
 * <h2>What a generator is</h2>
 *
 * <p>{@code function* g() { yield v; ... } } — the execution runs until the {@code yield}, hands
 * {@code v} to whoever drives it, and stops. It starts again when the driver says so, and it starts
 * again in the state the generator was left in.
 *
 * <p>So the TAC says three things: the instruction where the execution leaves is a suspend point,
 * the function names the state it can be resumed in, and the value the driver receives is in the
 * generator's output slot.
 *
 * <p>Each assertion here says ONE thing, so that a mutation can refute exactly one of them.
 */
@DisplayName("ArkTS: a generator as a state machine")
class ArktsGeneratorStateMachineTest {

  /** A generator that yields a call's result and then keeps going. */
  private static final String GENERATOR =
      """
      export function* gen(request: any): any {
        const first = yield eval(request["cmd"]);
        eval(request["cmd"]);
        return first;
      }
      """;

  /** A function with no yield at all: the control. */
  private static final String CONTROL =
      """
      export function plain(request: any): any {
        return eval(request["cmd"]);
      }
      """;

  @Test
  @DisplayName("the operand of a yield is evaluated before the execution leaves")
  void theOperandOfAYieldIsEvaluatedBeforeTheExecutionLeaves(@TempDir Path directory)
      throws IOException {
    TacFunction generator = theGenerator(translateAll(GENERATOR, directory, "app.ets"));

    assertThat(beforeTheSuspendPoint(generator).stream().map(instruction -> instruction.calleeSig))
        .as(
            "the operand is user code: a sink inside it is a sink inside the generator, and before"
                + " this the yield had no branch at all, so it was never even looked for -- the"
                + " position matters, because the body has a second sink AFTER the yield")
        .anyMatch(signature -> signature != null && signature.startsWith("eval.eval/"));
  }

  @Test
  @DisplayName("the generator names the state it can be resumed in")
  void theGeneratorNamesTheStateItCanBeResumedIn(@TempDir Path directory) throws IOException {
    TacFunction generator = theGenerator(translateAll(GENERATOR, directory, "app.ets"));

    assertThat(generator.resumePoints())
        .as("a generator has one state per yield, and the function names them")
        .isNotEmpty();
  }

  @Test
  @DisplayName("the state the generator names is a label in the instruction stream")
  void theStateTheGeneratorNamesIsALabelInTheInstructionStream(@TempDir Path directory)
      throws IOException {
    TacFunction generator = theGenerator(translateAll(GENERATOR, directory, "app.ets"));

    TacResumePoint resumePoint = generator.resumePoints().get(0);
    assertThat(labelNames(generator))
        .as("a resume point names a place, and the place has to be in the stream")
        .contains(resumePoint.label());
  }

  @Test
  @DisplayName("the instruction where the execution leaves is marked as a suspend point")
  void theInstructionWhereTheExecutionLeavesIsMarkedAsASuspendPoint(@TempDir Path directory)
      throws IOException {
    TacFunction generator = theGenerator(translateAll(GENERATOR, directory, "app.ets"));

    assertThat(generator.instructions())
        .as("without a marked instruction there is nothing that says where the execution leaves")
        .anyMatch(TacInstruction::isSuspending);
  }

  @Test
  @DisplayName("the suspend point is the instruction that jumps to the state")
  void theSuspendPointIsTheInstructionThatJumpsToTheState(@TempDir Path directory)
      throws IOException {
    TacFunction generator = theGenerator(translateAll(GENERATOR, directory, "app.ets"));

    TacInstruction suspend =
        generator.instructions().stream()
            .filter(TacInstruction::isSuspending)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no instruction is marked as a suspend point"));

    assertThat(suspend.label)
        .as(
            "the suspend point and the state it resumes in are the same fact seen from two ends,"
                + " and the jump says which state that is")
        .isEqualTo(generator.resumePoints().get(0).label());
  }

  @Test
  @DisplayName("the value the driver receives is put in the generator's output slot")
  void theValueTheDriverReceivesIsPutInTheGeneratorsOutputSlot(@TempDir Path directory)
      throws IOException {
    TacFunction generator = theGenerator(translateAll(GENERATOR, directory, "app.ets"));

    assertThat(generator.instructions())
        .as("the generator produces a value, and the TAC says where it is put")
        .anyMatch(
            instruction ->
                instruction.kind == TacInstruction.Kind.MOVE
                    && instruction.dest instanceof TacOperand.TacVar destination
                    && "gen.yielded".equals(destination.name()));
  }

  @Test
  @DisplayName("the output slot holds the value that was yielded")
  void theOutputSlotHoldsTheValueThatWasYielded(@TempDir Path directory) throws IOException {
    TacFunction generator = theGenerator(translateAll(GENERATOR, directory, "app.ets"));

    TacInstruction slot =
        generator.instructions().stream()
            .filter(
                instruction ->
                    instruction.kind == TacInstruction.Kind.MOVE
                        && instruction.dest instanceof TacOperand.TacVar destination
                        && "gen.yielded".equals(destination.name()))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("the generator's output slot is not in the TAC at all"));

    assertThat(definingInstruction(generator, slot.lhs1))
        .as(
            "the slot holds the operand's value, and the operand is a call to the sink -- a"
                + " constant or an unknown would say the generator produces something else")
        .isNotNull();
    assertThat(definingInstruction(generator, slot.lhs1).kind)
        .as("the operand is a call to the sink")
        .isEqualTo(TacInstruction.Kind.CALL);
  }

  @Test
  @DisplayName("a function with no yield has no state to be resumed in")
  void aFunctionWithNoYieldHasNoStateToBeResumedIn(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = translateAll(CONTROL, directory, "app.ets");

    TacFunction plain =
        functions.stream()
            .filter(function -> "plain".equals(function.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the function plain is not in the stream"));

    assertThat(plain.resumePoints())
        .as("an ordinary function is never suspended, so it names no state")
        .isEmpty();
    assertThat(plain.instructions())
        .as("and it has no suspend point either")
        .noneMatch(TacInstruction::isSuspending);
  }

  // ---- the harness ---------------------------------------------------------------------------

  /** The generator function of a translation. */
  private static TacFunction theGenerator(List<TacFunction> functions) {
    return functions.stream()
        .filter(function -> "gen".equals(function.name()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("the generator is not in the stream"));
  }

  /** The names of the labels in a function's instruction stream. */
  private static List<String> labelNames(TacFunction function) {
    return function.instructions().stream()
        .filter(instruction -> instruction.kind == TacInstruction.Kind.LABEL)
        .map(instruction -> instruction.label)
        .filter(label -> label != null)
        .toList();
  }

  /**
   * The instructions that come before the point where the execution leaves the function.
   *
   * <p><b>Why the position matters.</b> The body has a sink AFTER the yield as well, so "the stream
   * contains a call to the sink" does not say that the OPERAND was evaluated -- the second sink
   * would satisfy that on its own. The operand's call is the one BEFORE the suspend point.
   */
  private static List<TacInstruction> beforeTheSuspendPoint(TacFunction function) {
    List<TacInstruction> instructions = function.instructions();
    for (int i = 0; i < instructions.size(); i++) {
      if (instructions.get(i).isSuspending()) {
        return instructions.subList(0, i);
      }
    }
    throw new AssertionError("no instruction is marked as a suspend point");
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

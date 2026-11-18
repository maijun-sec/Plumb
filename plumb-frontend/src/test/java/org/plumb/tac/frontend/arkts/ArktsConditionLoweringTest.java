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
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacLabelRef;
import org.plumb.tac.ir.TacOperand;

/**
 * The ArkTS frontend: the conditions it evaluates and the jumps it takes.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>ArkTS is lowered by a sidecar of its own, against a compiler of its own, and it had the same
 * defect the TypeScript sidecar had: the condition of an {@code if} and of a {@code while} was
 * never evaluated. The TAC was well formed, every label resolved, and the branch tested nothing at
 * all.
 *
 * <p>So the assertions here are about SHAPES: a branch has a subject, a comparison was evaluated, a
 * jump goes to the loop the source named.
 */
@DisplayName("ArkTS: the conditions it evaluates and the jumps it takes")
class ArktsConditionLoweringTest {

  @Test
  @DisplayName("an if statement's condition is evaluated, not dropped")
  void anIfStatementsConditionIsEvaluated(@TempDir Path directory) throws IOException {
    String source =
        """
        export function pick(a: number, b: number): string {
          if (a === b) {
            return "eq";
          }
          return "ne";
        }
        """;
    TacFunction pick = translate(source, directory, "app.ets", "pick");

    assertThat(pick.instructions())
        .as("the comparison in the condition was never evaluated: the branch tests nothing")
        .filteredOn(i -> i.kind == TacInstruction.Kind.BINOP)
        .filteredOn(i -> i.binop == BinaryOperator.EQ)
        .hasSize(1);

    TacInstruction branch =
        pick.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.BRANCH)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the branch is not in the stream"));
    assertThat(branch.lhs1).as("a branch without a subject tests nothing").isNotNull();
    assertThat(branch.guard.subject())
        .as("a guard without a subject carries no path condition")
        .isNotNull();
  }

  @Test
  @DisplayName("a while statement's condition is evaluated, not dropped")
  void aWhileStatementsConditionIsEvaluated(@TempDir Path directory) throws IOException {
    String source =
        """
        export function spin(a: number, b: number): number {
          while (a < b) {
            a = a + 1;
          }
          return a;
        }
        """;
    TacFunction spin = translate(source, directory, "app.ets", "spin");

    assertThat(spin.instructions())
        .as("the comparison in the loop's condition was never evaluated")
        .filteredOn(i -> i.kind == TacInstruction.Kind.BINOP)
        .filteredOn(i -> i.binop == BinaryOperator.LT)
        .hasSize(1);
  }

  @Test
  @DisplayName("a labelled continue continues the loop the label names")
  void aLabelledContinueContinuesTheLoopTheLabelNames(@TempDir Path directory) throws IOException {
    // The `continue` is on the fifth line of the source below.
    String source =
        """
        export function loops(n: number): number {
          let total = 0;
          outer: for (let i = 0; i < n; i++) {
            for (let j = 0; j < n; j++) {
              if (j === 2) continue outer;
              total = total + j;
            }
          }
          return total;
        }
        """;
    TacFunction loops = translate(source, directory, "app.ets", "loops");

    List<String> loopHeads =
        loops.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.LABEL)
            .filter(i -> i.labelRef != null && i.labelRef.kind() == TacLabelRef.Kind.LOOP_HEAD)
            .map(i -> i.label)
            .toList();
    assertThat(loopHeads)
        .as("the two loops each have a head of their own")
        .hasSizeGreaterThanOrEqualTo(2);

    TacInstruction jump =
        loops.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.JUMP)
            .filter(i -> i.line == 5)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the continue is not in the stream"));
    assertThat(jump.label)
        .as(
            "a labelled continue continues the loop the LABEL names -- the outer one -- not the"
                + " innermost loop that happens to be inside it")
        .isEqualTo(loopHeads.get(0));
  }

  @Test
  @DisplayName("an object literal's spread reaches the object it builds")
  void anObjectLiteralsSpreadReachesTheObject(@TempDir Path directory) throws IOException {
    String source =
        """
        export function spread(rec: { x: string }): { x: string; y: string } {
          return { ...rec, y: "1" };
        }
        """;
    TacFunction spread = translate(source, directory, "app.ets", "spread");

    TacInstruction copy =
        spread.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.UNINTERPRETED)
            .filter(i -> "Object.assign/2".equals(i.calleeSig))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the spread was dropped: the object was built without the source's"
                            + " properties"));
    assertThat(copy.args)
        .as("the copy names the source it copies from")
        .filteredOn(operand -> operand instanceof TacOperand.TacVar)
        .extracting(operand -> ((TacOperand.TacVar) operand).name())
        .contains("rec");
  }

  /** The function with the given name, translated through the real sidecar. */
  private static TacFunction translate(
      String source, Path directory, String fileName, String functionName) throws IOException {
    Path file = directory.resolve(fileName);
    Files.writeString(file, source);

    try (ArktsTacFrontend frontend = new ArktsTacFrontend(SourceLanguage.ARKTS)) {
      assumeTrue(
          sidecarAvailable(),
          "the ArkTS front end needs Node and the sidecar's own compiler dependency: run npm ci in"
              + " plumb-frontend/arkts-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      for (TacFunction function : result.functions()) {
        if (functionName.equals(function.name())) {
          return function;
        }
      }
      throw new AssertionError("the function " + functionName + " is not in the stream");
    }
  }

  /** Whether the sidecar can run here: Node, the script, and the compiler it imports. */
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

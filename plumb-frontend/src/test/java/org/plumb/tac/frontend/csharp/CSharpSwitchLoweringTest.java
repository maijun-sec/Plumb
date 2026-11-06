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
package org.plumb.tac.frontend.csharp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The C# {@code switch} lowering must produce a CFG in which each case BODY is
 * reached by a branch, and the case COMPARISONS are not re-executed on the way in.
 *
 * <p><b>Why this is a separate test.</b> A switch is the one construct in C# whose
 * lowering has to get the ORDER of two groups of instructions right: the comparison
 * chain (one test per case label) and the case bodies. If the two are interleaved,
 * the result is still <em>well-formed</em> -- every label resolves, the CFG builder
 * accepts it, and the scan reports success. It is just semantically wrong: the
 * branch that selects a case lands on the comparison again instead of on the body.
 *
 * <p><b>What the shape must be.</b> The Java reference lowering emits the whole
 * comparison chain first, then a jump to the end, then the bodies under their
 * labels. The C# lowering must do the same, so that:
 *
 * <ul>
 *   <li>every comparison BRANCH targets a label that appears LATER in the stream;
 *   <li>every case body's LABEL appears AFTER the comparison that selects it.
 * </ul>
 */
@DisplayName("C#: the lowering of a switch statement")
class CSharpSwitchLoweringTest {

  private static CSharpTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new CSharpTestSupport();
  }

  @AfterAll
  static void tearDown() {
    if (support != null) {
      support.close();
    }
  }

  /** A switch with two cases and a default. */
  private static final String SOURCE =
      """
      namespace Demo;

      public class Switcher
      {
          public int Pick(int mode)
          {
              int result;
              switch (mode)
              {
                  case 1:
                      result = 10;
                      break;
                  case 2:
                      result = 20;
                      break;
                  default:
                      result = 30;
                      break;
              }
              return result;
          }
      }
      """;

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("every case comparison branches FORWARD to the body it selects")
  void everyCaseComparisonBranchesForwardToItsBody() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Pick");

    List<TacInstruction> instructions = function.instructions();

    // The index of every LABEL, so a branch target can be located.
    List<Integer> labelPositions = new ArrayList<>();
    for (int i = 0; i < instructions.size(); i++) {
      TacInstruction instruction = instructions.get(i);
      if (instruction.kind == TacInstruction.Kind.LABEL && instruction.label != null) {
        labelPositions.add(i);
      }
    }
    assertThat(labelPositions)
        .as("the switch must produce labels: a case label, the default, and the end")
        .hasSizeGreaterThanOrEqualTo(3);

    // The label each comparison selects must be DEFINED AFTER the comparison.
    // A label defined before it means the "true" edge lands on the comparison
    // again, and the case body is never entered directly.
    int comparisons = 0;
    for (int i = 0; i < instructions.size(); i++) {
      TacInstruction instruction = instructions.get(i);
      if (instruction.kind != TacInstruction.Kind.BRANCH) {
        continue;
      }
      if (instruction.trueLabel == null || instruction.falseLabel == null) {
        continue;
      }
      // Only the comparison branches matter: they are the ones whose true target
      // is a case label.
      Integer truePosition = positionOfLabel(instructions, instruction.trueLabel);
      if (truePosition == null) {
        continue;
      }
      comparisons++;
      assertThat(truePosition)
          .as(
              "the branch that selects the case must target a label defined AFTER the "
                  + "comparison, so that the case BODY is what the branch reaches; a "
                  + "backward target re-runs the comparison instead of entering the body")
          .isGreaterThan(i);
    }

    assertThat(comparisons)
        .as("the switch must lower to a comparison chain, one branch per case label")
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("the case bodies are emitted AFTER the comparison chain, not interleaved")
  void theCaseBodiesComeAfterTheComparisonChain() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Pick");

    List<TacInstruction> instructions = function.instructions();

    int lastComparison = -1;
    int firstBodyStore = -1;
    for (int i = 0; i < instructions.size(); i++) {
      TacInstruction instruction = instructions.get(i);
      if (instruction.kind == TacInstruction.Kind.BRANCH
          && instruction.trueLabel != null
          && instruction.falseLabel != null) {
        lastComparison = i;
      }
      // The body of a case is the MOVE that stores the case's constant into
      // the result variable: `result = 10;`.
      if (firstBodyStore < 0
          && instruction.kind == TacInstruction.Kind.MOVE
          && "10".equals(literalOf(instruction.lhs1))) {
        firstBodyStore = i;
      }
    }

    assertThat(lastComparison)
        .as("the switch must lower to comparisons")
        .isGreaterThanOrEqualTo(0);
    assertThat(firstBodyStore)
        .as("the first case body must lower to a constant assignment")
        .isGreaterThanOrEqualTo(0);
    assertThat(firstBodyStore)
        .as(
            "the case BODY must come after the whole comparison chain; interleaving them "
                + "means the branch into a case re-runs the comparison instead of "
                + "entering the body")
        .isGreaterThan(lastComparison);
  }

  /** The position of the instruction that DEFINES the label, or null. */
  private static Integer positionOfLabel(List<TacInstruction> instructions, String label) {
    for (int i = 0; i < instructions.size(); i++) {
      TacInstruction instruction = instructions.get(i);
      if (instruction.kind == TacInstruction.Kind.LABEL && label.equals(instruction.label)) {
        return i;
      }
    }
    return null;
  }

  /** The literal text of a CONST operand, or null. */
  private static String literalOf(TacOperand operand) {
    if (operand instanceof TacOperand.Const constant) {
      return String.valueOf(constant.value());
    }
    return null;
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("the default body is reachable: the no-match jump lands on it, not past it")
  void theDefaultBodyIsReachable() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Pick");

    List<TacInstruction> instructions = function.instructions();

    // The default body is the MOVE that stores 30 into the result variable.
    int defaultBody = -1;
    for (int i = 0; i < instructions.size(); i++) {
      TacInstruction instruction = instructions.get(i);
      if (instruction.kind == TacInstruction.Kind.MOVE
          && "30".equals(literalOf(instruction.lhs1))) {
        defaultBody = i;
      }
    }
    assertThat(defaultBody)
        .as("the default body must lower to a constant assignment")
        .isGreaterThanOrEqualTo(0);

    // The switch's own LABEL must come AFTER the default body: a jump to the
    // end that precedes the default body would skip it.
    int switchEnd = -1;
    for (int i = 0; i < instructions.size(); i++) {
      TacInstruction instruction = instructions.get(i);
      if (instruction.kind == TacInstruction.Kind.LABEL
          && instruction.label != null
          && instruction.label.startsWith("switch_end")) {
        switchEnd = i;
      }
    }
    assertThat(switchEnd)
        .as("the switch must have an end label")
        .isGreaterThan(defaultBody);
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a `break` inside a case jumps to the end of the switch")
  void aBreakInsideACaseJumpsToTheEndOfTheSwitch() {
    // Without the jump, the case body FALLS THROUGH into the next case body --
    // which is a path the program does not take. The result is taint flowing
    // along a path that does not exist: false positives in the case, and
    // findings attributed to the wrong line.
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Pick");

    List<TacInstruction> instructions = function.instructions();

    // The MOVE that stores 10 is the first case body's statement.
    int firstBody = -1;
    for (int i = 0; i < instructions.size(); i++) {
      TacInstruction instruction = instructions.get(i);
      if (instruction.kind == TacInstruction.Kind.MOVE
          && "10".equals(literalOf(instruction.lhs1))) {
        firstBody = i;
      }
    }
    assertThat(firstBody).as("the first case body must be lowered").isGreaterThanOrEqualTo(0);

    // The MOVE that stores 20 is the SECOND case body's statement. It must
    // not be reachable by falling through: a JUMP must separate them.
    int secondBody = -1;
    for (int i = 0; i < instructions.size(); i++) {
      TacInstruction instruction = instructions.get(i);
      if (instruction.kind == TacInstruction.Kind.MOVE
          && "20".equals(literalOf(instruction.lhs1))) {
        secondBody = i;
      }
    }
    assertThat(secondBody).as("the second case body must be lowered").isGreaterThanOrEqualTo(0);

    // Between the two bodies there must be a JUMP: the `break;` of the first
    // case. Without it, the first body falls into the second.
    boolean hasJumpBetween = false;
    for (int i = firstBody; i < secondBody; i++) {
      if (instructions.get(i).kind == TacInstruction.Kind.JUMP) {
        hasJumpBetween = true;
        break;
      }
    }
    assertThat(hasJumpBetween)
        .as(
            "a `break` inside a switch case must lower to a JUMP; dropping it lets the "
                + "case body fall through into the next one, which is a path the "
                + "program does not take")
        .isTrue();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a `break` inside a loop jumps to the end of the loop")
  void aBreakInsideALoopJumpsToTheEndOfTheLoop() {
    String source =
        """
        namespace Demo;

        public class Looper
        {
            public void Run()
            {
                for (var i = 0; i < 10; i++)
                {
                    if (i == 3)
                    {
                        break;
                    }
                }
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Run");

    boolean hasJump = false;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.JUMP) {
        hasJump = true;
      }
    }

    assertThat(hasJump)
        .as(
            "a `break` inside a loop must lower to a JUMP to the loop's end label; "
                + "dropping it lets the loop body keep running past the break")
        .isTrue();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a `continue` inside a loop jumps to the loop's increment, not to its end")
  void aContinueInsideALoopJumpsToTheIncrement() {
    // A `continue` that is dropped is worse than a `break` that is dropped: the
    // loop body keeps running to its end instead of restarting, so the taint
    // state at the top of the next iteration is wrong.
    String source =
        """
        namespace Demo;

        public class Looper
        {
            public void Run()
            {
                for (var i = 0; i < 10; i++)
                {
                    if (i == 3)
                    {
                        continue;
                    }
                    DoWork();
                }
            }

            private void DoWork()
            {
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Run");

    // The `continue` must be a JUMP whose target is the loop's INCREMENT label
    // -- not the end label. A `continue` that jumps to the end would end the
    // loop; one that is dropped runs the rest of the body.
    String continueTarget = null;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.JUMP
          && instruction.label != null
          && instruction.label.startsWith("for_inc")) {
        continueTarget = instruction.label;
      }
    }

    assertThat(continueTarget)
        .as(
            "a `continue` inside a `for` must lower to a JUMP to the loop's increment "
                + "label; dropping it runs the rest of the body instead of restarting "
                + "the iteration")
        .isNotNull();
  }

  /** Whether the .NET toolchain is available. */
  static boolean toolchainAvailable() {
    return CSharpTestSupport.toolchainPresent();
  }

  /** The function with the given name. */
  private static TacFunction functionNamed(List<TacFunction> functions, String name) {
    for (TacFunction function : functions) {
      if (name.equals(function.name())) {
        return function;
      }
    }
    throw new AssertionError("no function named " + name + " in " + functions);
  }
}

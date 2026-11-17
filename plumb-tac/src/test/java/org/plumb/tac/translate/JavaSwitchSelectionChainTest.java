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
package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

/**
 * The Java lowering of a switch: the SELECTION CHAIN.
 *
 * <p><b>What each test pins, and what used to be wrong.</b>
 *
 * <ul>
 *   <li>The false edge of a case's test went to the NEXT CASE'S BODY. So {@code case 1, 2 ->} never
 *       emitted the {@code disc == 2} test: the chain jumped straight into the body. A case the
 *       program never selects was indistinguishable from one it does.
 *   <li>A {@code default} in the middle was an unconditional jump placed inside the test chain, so
 *       every test after it was unreachable -- the cases after a default were never selected.
 *   <li>A switch EXPRESSION's arms were laid out consecutively with no jump out of them, so every
 *       arm ran. {@code case 1 -> f(); default -> g();} called both.
 *   <li>A pattern GUARD ({@code when}) was not lowered at all: the guard expression was missing
 *       from the TAC, and an arm whose guard failed fell into the next arm's body.
 * </ul>
 */
class JavaSwitchSelectionChainTest {

  private static TacProgram translate(String source) {
    ParsedSource parsed = new JavaSourceParser().parseFromString(Path.of("Switch.java"), source);
    return new TacTranslator(parsed).translate();
  }

  /** The function whose name ends with the given suffix, or null. */
  private static TacFunction functionNamed(TacProgram program, String suffix) {
    for (TacFunction fn : program.functions()) {
      if (fn.name().endsWith(suffix)) {
        return fn;
      }
    }
    return null;
  }

  /** Every instruction of the given function. */
  private static List<TacInstruction> instructions(TacFunction fn) {
    return fn == null ? List.of() : fn.instructions();
  }

  /** The number of comparisons of the form {@code something == something}. */
  private static long equalityTests(TacFunction fn) {
    return instructions(fn).stream()
        .filter(i -> i.kind == TacInstruction.Kind.BINOP && i.binop == BinaryOperator.EQ)
        .count();
  }

  @Test
  @DisplayName("a case with two labels tests both of them")
  void aCaseWithTwoLabelsTestsBothOfThem() {
    TacProgram program =
        translate(
            "class Multi {\n"
                + "  int pick(int x) {\n"
                + "    switch (x) {\n"
                + "      case 1, 2:\n"
                + "        return 10;\n"
                + "      default:\n"
                + "        return 20;\n"
                + "    }\n"
                + "  }\n"
                + "}\n");

    TacFunction pick = functionNamed(program, "pick");
    assertThat(pick).as("the method must be in the program").isNotNull();
    assertThat(equalityTests(pick))
        .as("`case 1, 2` must test BOTH labels, not jump into the body after the first")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("a default in the middle does not cut the tests after it short")
  void aDefaultInTheMiddleDoesNotCutTheTestsAfterItShort() {
    TacProgram program =
        translate(
            "class Middle {\n"
                + "  int pick(int x) {\n"
                + "    switch (x) {\n"
                + "      case 1:\n"
                + "        return 10;\n"
                + "      default:\n"
                + "        return 20;\n"
                + "      case 2:\n"
                + "        return 30;\n"
                + "    }\n"
                + "  }\n"
                + "}\n");

    TacFunction pick = functionNamed(program, "pick");
    assertThat(pick).as("the method must be in the program").isNotNull();
    assertThat(equalityTests(pick))
        .as("the case AFTER the default must still be tested")
        .isEqualTo(2);
    assertThat(instructions(pick))
        .as("the default is the target of the last test, not a jump that skips the rest")
        .anyMatch(
            i ->
                i.kind == TacInstruction.Kind.BRANCH
                    && i.falseLabel != null
                    && i.falseLabel.startsWith("L_CASE_")
                    && !i.falseLabel.startsWith("L_SW_TEST_"));
  }

  @Test
  @DisplayName("a switch expression runs one arm, not all of them")
  void aSwitchExpressionRunsOneArmNotAllOfThem() {
    TacProgram program =
        translate(
            "class Expr {\n"
                + "  int pick(int x) {\n"
                + "    int v = switch (x) {\n"
                + "      case 1 -> 10;\n"
                + "      default -> 20;\n"
                + "    };\n"
                + "    return v;\n"
                + "  }\n"
                + "}\n");

    TacFunction pick = functionNamed(program, "pick");
    assertThat(pick).as("the method must be in the program").isNotNull();
    long jumpsOut =
        instructions(pick).stream()
            .filter(
                i ->
                    i.kind == TacInstruction.Kind.JUMP
                        && i.label != null
                        && i.label.startsWith("L_SWEXPR_END"))
            .count();
    assertThat(jumpsOut)
        .as("every arm must LEAVE the switch expression, or all of them run")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("a pattern guard is a second test that falls to the next case")
  void aPatternGuardIsASecondTestThatFallsToTheNextCase() {
    TacProgram program =
        translate(
            "class Guarded {\n"
                + "  int pick(Object x) {\n"
                + "    switch (x) {\n"
                + "      case Integer i when i > 0:\n"
                + "        return 1;\n"
                + "      case Integer j:\n"
                + "        return 2;\n"
                + "      default:\n"
                + "        return 3;\n"
                + "    }\n"
                + "  }\n"
                + "}\n");

    TacFunction pick = functionNamed(program, "pick");
    assertThat(pick).as("the method must be in the program").isNotNull();
    List<TacInstruction> branches = new ArrayList<>();
    for (TacInstruction ins : instructions(pick)) {
      if (ins.kind == TacInstruction.Kind.BRANCH) {
        branches.add(ins);
      }
    }
    assertThat(branches.size())
        .as("a guarded arm has TWO tests: the type test and the guard")
        .isGreaterThanOrEqualTo(3);
    assertThat(branches)
        .as("a failing guard must fall to the NEXT case's test, not to the default")
        .anyMatch(
            i ->
                i.falseLabel != null
                    && i.falseLabel.startsWith("L_SW_TEST_1")
                    && !i.falseLabel.startsWith("L_CASE_"));
  }
}

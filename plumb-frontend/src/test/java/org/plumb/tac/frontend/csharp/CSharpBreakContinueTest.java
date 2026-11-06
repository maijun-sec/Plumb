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

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * A C# {@code break} and {@code continue} are JUMPS, and they must lower to one.
 *
 * <p><b>Why this needs its own test.</b> Dropping them does not make the TAC
 * smaller; it makes the TAC describe a control flow the program does not have:
 *
 * <ul>
 *   <li>a dropped {@code break} inside a switch lets the case body fall through
 *       into the next case, so the dataflow engine follows a path the program
 *       never takes;
 *   <li>a dropped {@code continue} inside a loop runs the rest of the body
 *       instead of restarting the iteration, so the taint state at the top of
 *       the next iteration is wrong.
 * </ul>
 *
 * <p>Both are invisible in a scan that reports success.
 */
@DisplayName("C#: the lowering of break and continue")
class CSharpBreakContinueTest {

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

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a `continue` in a `for` jumps to the increment, skipping the rest of the body")
  void aContinueInAForJumpsToTheIncrement() {
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
    List<TacInstruction> instructions = function.instructions();

    int continueJump = -1;
    int doWork = -1;
    for (int i = 0; i < instructions.size(); i++) {
      TacInstruction instruction = instructions.get(i);
      if (instruction.kind == TacInstruction.Kind.JUMP
          && instruction.label != null
          && instruction.label.startsWith("for_inc")) {
        continueJump = i;
      }
      if (instruction.kind == TacInstruction.Kind.CALL
          && instruction.calleeSig != null
          && instruction.calleeSig.contains("DoWork")) {
        doWork = i;
      }
    }

    assertThat(continueJump)
        .as("the `continue` must lower to a JUMP to the loop's increment label")
        .isGreaterThanOrEqualTo(0);
    assertThat(doWork)
        .as("the loop body must be lowered")
        .isGreaterThanOrEqualTo(0);
    assertThat(continueJump)
        .as(
            "the `continue` must come BEFORE the rest of the loop body, so that the "
                + "jump skips it -- a `continue` that comes after the body lets the "
                + "body run on the iteration that was supposed to skip it")
        .isLessThan(doWork);
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a `break` in a `while` jumps to the loop's end")
  void aBreakInAWhileJumpsToTheEnd() {
    String source =
        """
        namespace Demo;

        public class Looper
        {
            public void Run()
            {
                var i = 0;
                while (i < 10)
                {
                    if (i == 3)
                    {
                        break;
                    }
                    i = i + 1;
                }
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Run");

    boolean hasBreakJump = false;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.JUMP
          && instruction.label != null
          && instruction.label.startsWith("while_end")) {
        hasBreakJump = true;
      }
    }

    assertThat(hasBreakJump)
        .as(
            "a `break` inside a `while` must lower to a JUMP to the loop's end label; "
                + "dropping it lets the loop keep running past the break")
        .isTrue();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a `break` inside a switch case jumps to the end of the SWITCH, not the loop")
  void aBreakInsideASwitchJumpsToTheSwitchEnd() {
    // The innermost context wins: a `break` inside a switch inside a loop
    // breaks the SWITCH. Getting this wrong makes the loop's tail unreachable.
    String source =
        """
        namespace Demo;

        public class Looper
        {
            public void Run(int mode)
            {
                for (var i = 0; i < 10; i++)
                {
                    switch (mode)
                    {
                        case 1:
                            break;
                        default:
                            break;
                    }
                }
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Run");

    boolean hasSwitchEndJump = false;
    boolean hasLoopEndJump = false;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind != TacInstruction.Kind.JUMP || instruction.label == null) {
        continue;
      }
      if (instruction.label.startsWith("switch_end")) {
        hasSwitchEndJump = true;
      }
      if (instruction.label.startsWith("for_end")) {
        hasLoopEndJump = true;
      }
    }

    assertThat(hasSwitchEndJump)
        .as("the `break` inside the switch must jump to the switch's end label")
        .isTrue();
    assertThat(hasLoopEndJump)
        .as(
            "the `break` inside the switch must NOT jump to the loop's end label: the "
                + "innermost context wins, and breaking the loop instead of the "
                + "switch makes the rest of the loop body unreachable")
        .isFalse();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a `continue` inside a switch continues the enclosing LOOP")
  void aContinueInsideASwitchContinuesTheLoop() {
    // In C#, `continue` inside a switch continues the enclosing LOOP. The
    // switch has no continue target of its own, so a `continue` there must not
    // be lowered into a jump to the switch's end.
    String source =
        """
        namespace Demo;

        public class Looper
        {
            public void Run(int mode)
            {
                for (var i = 0; i < 10; i++)
                {
                    switch (mode)
                    {
                        case 1:
                            continue;
                        default:
                            break;
                    }
                }
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Run");

    boolean hasLoopIncrementJump = false;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.JUMP
          && instruction.label != null
          && instruction.label.startsWith("for_inc")) {
        hasLoopIncrementJump = true;
      }
    }

    assertThat(hasLoopIncrementJump)
        .as(
            "a `continue` inside a switch inside a `for` must continue the LOOP, so it "
                + "must lower to a JUMP to the loop's increment label")
        .isTrue();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a `continue` in a `while` jumps to the condition, not to the body")
  void aContinueInAWhileJumpsToTheCondition() {
    String source =
        """
        namespace Demo;

        public class Looper
        {
            public void Run()
            {
                var i = 0;
                while (i < 10)
                {
                    if (i == 3)
                    {
                        continue;
                    }
                    i = i + 1;
                }
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Run");

    boolean hasConditionJump = false;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.JUMP
          && instruction.label != null
          && instruction.label.startsWith("while_cond")) {
        hasConditionJump = true;
      }
    }

    assertThat(hasConditionJump)
        .as(
            "a `continue` inside a `while` must lower to a JUMP to the loop's "
                + "condition label; jumping to the body instead would run the body "
                + "again without re-testing the condition")
        .isTrue();
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

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
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.translate.TacTranslator;

/**
 * The guards in force at an instruction are the guards its PATH establishes — not every guard the
 * function contains.
 *
 * <p><b>Why this is a separate question.</b> A function can contain two guards that contradict each
 * other and still have a reachable path to a sink:
 *
 * <pre>
 *   if (n &gt; 5) { ... }        // n &gt; 5
 *   if (n &lt; 3) { ... }        // n &lt; 3
 *   sink(n);                    // reachable with n = 1
 * </pre>
 *
 * <p>The two guards cannot both hold, but the path to the sink does not require both of them. A
 * check that reads the function's WHOLE guard set would conclude that the sink is unreachable and
 * suppress a finding that is real.
 *
 * <p>The guard tracker's active stack is already path-scoped — a guard is pushed on its BRANCH and
 * popped at the end of its block — so the fix is to record the stack's contents at each instruction
 * position, and let a sink ask "what was in force here?".
 */
class PathScopedGuardTest {

  @TempDir Path tmp;

  /**
   * Two SEQUENTIAL guards on the same variable, with the sink after both.
   *
   * <p>The sink is reachable with {@code n = 1}: the first guard fails, the second holds. So no
   * path to the sink requires both guards.
   */
  private static final String SEQUENTIAL =
      """
      class T {
        void run(int n) {
          if (n > 5) {
            System.out.println("big");
          }
          if (n < 3) {
            System.out.println("small");
          }
          System.out.println(n);
        }
      }
      """;

  /**
   * Two NESTED guards on the same variable, with the sink inside both.
   *
   * <p>Reaching the sink requires {@code n > 5} AND {@code n < 3}, which is unsatisfiable. So the
   * sink is genuinely unreachable.
   */
  private static final String NESTED =
      """
      class T {
        void run(int n) {
          if (n > 5) {
            if (n < 3) {
              System.out.println(n);
            }
          }
        }
      }
      """;

  @Test
  @DisplayName("the guards at a position are the ones its path establishes")
  void theGuardsAtAPositionAreThePathScopedOnes() throws Exception {
    TacFunction fn = translate(SEQUENTIAL);

    // Walk the function the way the engine does, recording the active guards at each
    // position, and find the sink: the PRINTLN that is not inside either if-block.
    GuardTracker tracker = new GuardTracker();
    int sinkIndex = -1;
    for (int i = 0; i < fn.instructionCount(); i++) {
      TacInstruction ins = fn.instructionAt(i);
      tracker.recordPosition(i);
      tracker.track(ins);
      if (isUnconditionalPrint(ins)) {
        sinkIndex = i;
      }
    }

    assertThat(sinkIndex).as("the fixture must have a sink after both if-blocks").isGreaterThan(0);
    assertThat(tracker.guardsAt(sinkIndex))
        .as(
            "both if-blocks have ENDED at the sink, so no guard is in force there -- and that is"
                + " what makes the sink reachable")
        .isEmpty();
  }

  @Test
  @DisplayName("a nested pair of contradictory guards is in force at the sink")
  void aNestedPairIsInForceAtTheSink() throws Exception {
    TacFunction fn = translate(NESTED);

    GuardTracker tracker = new GuardTracker();
    int sinkIndex = -1;
    for (int i = 0; i < fn.instructionCount(); i++) {
      TacInstruction ins = fn.instructionAt(i);
      tracker.recordPosition(i);
      tracker.track(ins);
      if (isUnconditionalPrint(ins)) {
        sinkIndex = i;
      }
    }

    assertThat(sinkIndex).as("the fixture must have a sink inside both if-blocks").isGreaterThan(0);
    assertThat(tracker.guardsAt(sinkIndex))
        .as("reaching the sink requires both guards, so both are in force")
        .hasSize(2);
  }

  @Test
  @DisplayName("the function-wide union is NOT the path-scoped set")
  void theFunctionWideUnionIsNotThePathScopedSet() throws Exception {
    TacFunction fn = translate(SEQUENTIAL);

    GuardTracker tracker = new GuardTracker();
    for (int i = 0; i < fn.instructionCount(); i++) {
      TacInstruction ins = fn.instructionAt(i);
      tracker.recordPosition(i);
      tracker.track(ins);
    }

    assertThat(tracker.allGuardsSeen()).as("the function does contain both guards").hasSize(2);
    assertThat(tracker.guardsAt(fn.instructionCount() - 1))
        .as("but neither is in force at the end of the function")
        .isEmpty();
  }

  /** Translates a source and returns its single function. */
  private TacFunction translate(String source) throws Exception {
    Path file = tmp.resolve("T" + source.hashCode() + ".java");
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacFunction fn = new TacTranslator(ps).translate().functions().get(0);
    return fn;
  }

  /**
   * Whether this instruction is the sink: a PRINTLN of a variable, which is the last one in the
   * fixture's shape.
   */
  private static boolean isUnconditionalPrint(TacInstruction ins) {
    return ins != null
        && ins.kind == TacInstruction.Kind.CALL
        && ins.calleeSig != null
        && ins.calleeSig.contains("println");
  }
}

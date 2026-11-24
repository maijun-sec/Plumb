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
package org.plumb.tac.ir;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The CFG: the edges a suspended execution takes when it comes back.
 *
 * <h2>What a resume point is for</h2>
 *
 * <p>A generator's {@code yield} leaves the function, and the execution comes back at a state the
 * function named. The TAC says which instruction leaves ({@link TacInstruction.Effect#SUSPEND}) and
 * the function says where it comes back to ({@link TacFunction#resumePoints()}).
 *
 * <h2>Why the edge has to be built</h2>
 *
 * <p>The instruction stream is linear, and a linear stream connects a suspend point to the code
 * after it by falling through. That is enough when the resume point is the next thing in the stream
 * -- and it is NOT enough when it is not. A generator that yields inside a loop resumes at the
 * loop's head, which is EARLIER in the stream, and a fall-through cannot reach it.
 *
 * <p>Without the edge, the code after the yield would be unreachable, and the taint that reaches it
 * would be lost: a sink that only runs after a resume would stop being reported. That is the
 * failure this test exists to prevent.
 *
 * <p>Each assertion here says ONE thing, so that a mutation can refute exactly one of them.
 */
@DisplayName("The CFG: a suspended execution's way back")
class TacCfgResumeEdgeTest {

  /** The label the execution resumes at. */
  private static final String RESUME = "L_resume";

  /** The label the ordinary flow continues at. */
  private static final String AFTER = "L_after";

  @Test
  @DisplayName("a suspend point reaches the resume point, even when the resume point is earlier")
  void aSuspendPointReachesTheResumePointEvenWhenTheResumePointIsEarlier() {
    TacFunction fn = generatorThatResumesAtItsHead();
    TacCfg cfg = TacCfgBuilder.build(fn);

    int suspendBlock = blockOf(cfg, fn, RESUME + "_body");
    int resumeBlock = blockOf(cfg, fn, RESUME);

    assertThat(cfg.successors().get(suspendBlock))
        .as(
            "the execution is resumed at the loop's head, and the head is earlier in the stream,"
                + " so the fall-through cannot get there")
        .contains(resumeBlock);
  }

  @Test
  @DisplayName("a suspend point keeps its ordinary successors as well")
  void aSuspendPointKeepsItsOrdinarySuccessorsAsWell() {
    TacFunction fn = generatorThatResumesAtItsHead();
    TacCfg cfg = TacCfgBuilder.build(fn);

    int suspendBlock = blockOf(cfg, fn, RESUME + "_body");
    int afterBlock = blockOf(cfg, fn, AFTER);

    assertThat(cfg.successors().get(suspendBlock))
        .as("the edge is added, not substituted: the code after the suspend point still runs")
        .contains(afterBlock);
  }

  @Test
  @DisplayName("a function with no resume point gets no such edge")
  void aFunctionWithNoResumePointGetsNoSuchEdge() {
    TacFunction fn = fnWithoutResumePoints(generatorThatResumesAtItsHead().instructions());
    TacCfg cfg = TacCfgBuilder.build(fn);

    int suspendBlock = blockOf(cfg, fn, RESUME + "_body");
    int resumeBlock = blockOf(cfg, fn, RESUME);

    assertThat(cfg.successors().get(suspendBlock))
        .as(
            "without a resume point there is nowhere to come back to, and an edge to a label the"
                + " function never named would be a fact about nothing")
        .doesNotContain(resumeBlock);
  }

  // ---- the harness ---------------------------------------------------------------------------

  /**
   * A generator that yields inside a loop.
   *
   * <pre>
   *   L_resume:            the state the execution resumes in -- the loop's head
   *     t = MOVE 1
   *     JUMP L_body
   *   L_resume_body:
   *     MOVE gen.yielded = t   [SUSPEND]   the execution leaves the function here
   *     JUMP L_after
   *   L_after:
   *     RETURN
   * </pre>
   *
   * <p>The suspend point's ordinary successor is {@code L_after}; the resume point is {@code
   * L_resume}, which is earlier in the stream. Nothing but the resume edge connects them.
   */
  private static TacFunction generatorThatResumesAtItsHead() {
    TacInstruction head = TacInstruction.builder(TacInstruction.Kind.LABEL).label(RESUME).build();
    TacInstruction move =
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(new TacOperand.TacVar("t", TacType.INT, false))
            .lhs1(TacOperand.Const.ofInt(1))
            .build();
    TacInstruction jumpToBody =
        TacInstruction.builder(TacInstruction.Kind.JUMP).label(RESUME + "_body").build();
    TacInstruction bodyLabel =
        TacInstruction.builder(TacInstruction.Kind.LABEL).label(RESUME + "_body").build();
    TacInstruction suspend =
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(new TacOperand.TacVar("gen.yielded", TacType.UNKNOWN, false))
            .lhs1(new TacOperand.TacVar("t", TacType.INT, false))
            .effect(TacInstruction.Effect.SUSPEND)
            .build();
    TacInstruction jumpAfter =
        TacInstruction.builder(TacInstruction.Kind.JUMP).label(AFTER).build();
    TacInstruction afterLabel =
        TacInstruction.builder(TacInstruction.Kind.LABEL).label(AFTER).build();
    TacInstruction ret = TacInstruction.builder(TacInstruction.Kind.RETURN).build();

    return withResumePoints(
        List.of(head, move, jumpToBody, bodyLabel, suspend, jumpAfter, afterLabel, ret));
  }

  /** The same function, with its resume points taken away. */
  private static TacFunction fnWithoutResumePoints(List<TacInstruction> instructions) {
    return new TacFunction(
        "gen",
        "gen/0",
        TacType.VOID,
        List.of(),
        List.of(),
        instructions,
        List.of(),
        List.of(),
        java.util.Map.of(),
        java.util.Map.of(),
        null,
        SourceOrigin.SOURCE,
        List.of(),
        SourceLanguage.TYPESCRIPT,
        IrPrecision.SOURCE_DEGRADED,
        false,
        java.util.Map.of(),
        null,
        List.of(),
        null,
        List.of());
  }

  /** The same function, with one resume point. */
  private static TacFunction withResumePoints(List<TacInstruction> instructions) {
    return new TacFunction(
        "gen",
        "gen/0",
        TacType.VOID,
        List.of(),
        List.of(),
        instructions,
        List.of(),
        List.of(),
        java.util.Map.of(),
        java.util.Map.of(),
        null,
        SourceOrigin.SOURCE,
        List.of(),
        SourceLanguage.TYPESCRIPT,
        IrPrecision.SOURCE_DEGRADED,
        false,
        java.util.Map.of(),
        null,
        List.of(),
        null,
        List.of(TacResumePoint.suspend(RESUME)));
  }

  /** The block that the given label starts. */
  private static int blockOf(TacCfg cfg, TacFunction fn, String label) {
    List<TacInstruction> instructions = fn.instructions();
    for (int i = 0; i < instructions.size(); i++) {
      TacInstruction instruction = instructions.get(i);
      if (instruction.kind == TacInstruction.Kind.LABEL && label.equals(instruction.label)) {
        for (TacBasicBlock block : cfg.blocks()) {
          if (block.firstIndex() == i) {
            return block.id();
          }
        }
      }
    }
    throw new AssertionError("no block starts at the label " + label);
  }
}

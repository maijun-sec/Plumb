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
package org.plumb.dfa.checkers.controlflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * A release that a loop defers to the end of the function.
 *
 * <p><b>Why the test builds the TAC by hand.</b> The defect needs two things the engine has to be
 * told about: that the call is DEFERRED (it runs at the function's exit, not where it is written),
 * and that it sits inside a loop. The first comes from the frontend that knows the language's
 * primitive; the second comes from the control-flow graph.
 *
 * <p>Building the TAC by hand puts both in the test, so the test measures the RULE. A test that
 * went through the Go front end would measure the front end as well, and would silently skip itself
 * when the Go toolchain is not on the path -- which is exactly when a rule like this needs to be
 * measured.
 */
@DisplayName("CWE-772: a release a loop defers to the end of the function")
class DeferredReleaseInLoopCheckerTest {

  private static final String RULE_ID = "plumb.dfa-controlflow-deferred-release-in-loop";

  @Test
  @DisplayName("a deferred release inside a loop is reported")
  void aDeferredReleaseInsideALoopIsReported() {
    List<Finding> findings =
        runOn(
            function(
                "use",
                List.of(
                    label("L_cond"),
                    branch("L_body", "L_exit"),
                    label("L_body"),
                    call("os.File.Close/0", true),
                    jump("L_cond"),
                    label("L_exit"),
                    returnInstruction())));

    assertThat(findings)
        .as("the release runs when the function returns, so every iteration holds the file")
        .hasSize(1);
    assertThat(findings.get(0).cwe()).isEqualTo("CWE-772");
  }

  @Test
  @DisplayName("a deferred release outside a loop is not reported")
  void aDeferredReleaseOutsideALoopIsNotReported() {
    List<Finding> findings =
        runOn(function("use", List.of(call("os.File.Close/0", true), returnInstruction())));

    assertThat(findings)
        .as("a defer outside a loop runs at the exit, which is what it is for")
        .isEmpty();
  }

  @Test
  @DisplayName("a release inside a loop that is not deferred is not reported")
  void aReleaseInsideALoopThatIsNotDeferredIsNotReported() {
    List<Finding> findings =
        runOn(
            function(
                "use",
                List.of(
                    label("L_cond"),
                    branch("L_body", "L_exit"),
                    label("L_body"),
                    call("os.File.Close/0", false),
                    jump("L_cond"),
                    label("L_exit"),
                    returnInstruction())));

    assertThat(findings)
        .as("the release happens at the end of the iteration, so nothing is held")
        .isEmpty();
  }

  @Test
  @DisplayName("a deferred call that does not release anything is not reported")
  void aDeferredCallThatDoesNotReleaseAnythingIsNotReported() {
    List<Finding> findings =
        runOn(
            function(
                "use",
                List.of(
                    label("L_cond"),
                    branch("L_body", "L_exit"),
                    label("L_body"),
                    call("log.Logger.Print/1", true),
                    jump("L_cond"),
                    label("L_exit"),
                    returnInstruction())));

    assertThat(findings).as("a deferred print postpones nothing that holds a resource").isEmpty();
  }

  /** Analyses one function with the checker and returns the findings it produced. */
  private List<Finding> runOn(TacFunction function) {
    TacProgram program = new TacProgram(Path.of("DeferredRelease.java"), "DeferredRelease");
    program.addFunction(function);
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new DeferredReleaseInLoopChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (RULE_ID.equals(f.ruleId())) {
        out.add(f);
      }
    }
    return out;
  }

  /** A function with the given instructions and no parameters. */
  private static TacFunction function(String name, List<TacInstruction> instructions) {
    return new TacFunction(name, name, null, List.of(), List.of(), instructions);
  }

  private static TacInstruction label(String name) {
    return TacInstruction.builder(TacInstruction.Kind.LABEL).label(name).build();
  }

  private static TacInstruction jump(String target) {
    return TacInstruction.builder(TacInstruction.Kind.JUMP).label(target).build();
  }

  private static TacInstruction branch(String trueLabel, String falseLabel) {
    return TacInstruction.builder(TacInstruction.Kind.BRANCH)
        .trueLabel(trueLabel)
        .falseLabel(falseLabel)
        .build();
  }

  private static TacInstruction returnInstruction() {
    return TacInstruction.builder(TacInstruction.Kind.RETURN).build();
  }

  /** A call, deferred or not. */
  private static TacInstruction call(String calleeSig, boolean deferred) {
    TacOperand.TacVar receiver = new TacOperand.TacVar("file", null, false);
    TacInstruction.Builder builder =
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .dest(new TacOperand.TacVar("t_t0", null, true))
            .calleeSig(calleeSig)
            .args(List.of(receiver))
            .line(12)
            .column(5);
    if (deferred) {
      builder.effect(TacInstruction.Effect.DEFERRED);
    }
    return builder.build();
  }
}

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
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.ModelCoverageComputer;
import org.plumb.reporter.ModelCoverage;
import org.plumb.tac.ir.TacDrop;
import org.plumb.tac.ir.TacProgram;

/**
 * The coverage report has to say WHICH construct was lost, not only how many losses there were.
 *
 * <h2>Why this is worth pinning</h2>
 *
 * <p>A drop id is a promise: the front end says "this construct can be lost, and when it is, it is
 * this id". A count says how many losses there were; it does not say which construct was lost, so a
 * reader cannot tell a project that lost one unfamiliar construct from a project that lost a
 * familiar one ten times. The id is what makes the loss nameable, and the name is what a report
 * groups by.
 *
 * <p><b>Why the two scopes are kept apart.</b> A function-level loss lowered that function's
 * precision, so it already moved the {@code functionsAtFullPrecision} count. A program-level loss
 * belongs to no function: it lowered no precision, and it would be invisible if the two were mixed
 * into one pile. The design asks for the program-level losses to be listed in their own column, and
 * this is that column.
 */
@DisplayName("Coverage: the losses are grouped by the construct that was lost")
class ProgramDropCoverageTest {

  private static final EngineConfig CONFIG = ConfigLoader.loadFromClasspath();

  @Test
  @DisplayName("a program-level loss is listed under its own id")
  void aProgramLevelLossIsListedUnderItsOwnId() {
    TacProgram program = new TacProgram(Path.of("Demo.java"), "Demo");
    program.addProgramDrop(
        new TacDrop(
            "solver.fp.unavailable",
            "the floating point boundary is not modelled",
            TacDrop.Impact.SEMANTICS,
            0,
            0,
            TacDrop.Scope.PROGRAM));

    ModelCoverage coverage = ModelCoverageComputer.compute(List.of(program), CONFIG);

    assertThat(coverage.drops().byProgram())
        .as("a program-level loss belongs to no function, so it needs its own group")
        .containsEntry("solver.fp.unavailable", 1);
    assertThat(coverage.drops().byFunction())
        .as("and it must not be filed under the functions")
        .isEmpty();
    assertThat(coverage.toJson())
        .as("the record travels as JSON, and the group travels with it")
        .contains("\"programDrops\":{\"solver.fp.unavailable\":1}");
    assertThat(coverage.toJson()).contains("\"declaredDrops\":1");
  }

  @Test
  @DisplayName("the same loss twice is counted twice")
  void theSameLossTwiceIsCountedTwice() {
    TacProgram program = new TacProgram(Path.of("Demo.java"), "Demo");
    program.addProgramDrop(
        new TacDrop(
            "vendor.unresolved",
            "the vendored dependency is not named",
            TacDrop.Impact.SEMANTICS,
            0,
            0,
            TacDrop.Scope.PROGRAM));
    program.addProgramDrop(
        new TacDrop(
            "vendor.unresolved",
            "the vendored dependency is not named",
            TacDrop.Impact.SEMANTICS,
            0,
            0,
            TacDrop.Scope.PROGRAM));

    ModelCoverage coverage = ModelCoverageComputer.compute(List.of(program), CONFIG);

    assertThat(coverage.drops().byProgram())
        .as("a reader wants to know that the construct was lost twice")
        .containsEntry("vendor.unresolved", 2);
    assertThat(coverage.drops().total()).isEqualTo(2);
  }

  @Test
  @DisplayName("a program with no loss reports none")
  void aProgramWithNoLossReportsNone() {
    TacProgram program = new TacProgram(Path.of("Demo.java"), "Demo");

    ModelCoverage coverage = ModelCoverageComputer.compute(List.of(program), CONFIG);

    assertThat(coverage.drops().byProgram()).isEmpty();
    assertThat(coverage.drops().byFunction()).isEmpty();
    assertThat(coverage.drops().total()).isZero();
    assertThat(coverage.toJson()).contains("\"declaredDrops\":0");
  }
}

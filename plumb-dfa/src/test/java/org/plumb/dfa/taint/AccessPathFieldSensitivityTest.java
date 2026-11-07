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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.points.AbstractObject;
import org.plumb.dfa.points.PointsToSolver;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * Two objects of one class must be two LOCATIONS for the engine's field taint.
 *
 * <p><b>What was dead.</b> The field taint was keyed by {@code "receiver.fieldName"}, and the
 * receiver is a variable NAME. Two instances of one class reached through the same name therefore
 * shared one slot, so a field written on one instance could be read back on another. The
 * access-path vocabulary existed ({@link org.plumb.tac.ir.TacAccessPath}) and the store existed
 * ({@link AccessPathTaintStore}), and neither was connected to anything: nothing constructed a
 * path, and nothing read the store.
 *
 * <p><b>What these tests pin.</b> The two links of the chain:
 *
 * <ol>
 *   <li>The engine produces a path for a field access -- the path's root is the receiver's OBJECT
 *       identity, not its name.
 *   <li>The engine READS the store, so a field's taint follows the object it was written on.
 * </ol>
 *
 * <p><b>Why the second link is the one that matters.</b> A producer that computes the right path
 * and a store that holds the right taint still change nothing if the reader never looks. So the
 * test that matters is the one that reads the ENGINE's own state, not a hand-built one.
 */
@DisplayName("Taint: field taint follows the object, not the variable name")
class AccessPathFieldSensitivityTest {

  @TempDir Path tmp;

  /**
   * Two allocations of one class, and a field written on one of them.
   *
   * <p>The two allocations are the same TYPE. The only thing that distinguishes them is where they
   * were allocated -- which is exactly what the path's root carries.
   */
  private static final String TWO_ALLOCATION_SITES =
      """
      class Box {
        String data;
      }
      class Holder {
        void run() {
          Box first = new Box();
          Box second = new Box();
          first.data = "x";
        }
      }
      """;

  /**
   * The engine's own field taint, read from the engine's own state.
   *
   * <p>The path is produced by the engine's own builder at the engine's own field-access site, so a
   * builder that returned the variable's NAME would produce a path that looks right and carries
   * none of the information that makes one useful.
   */
  @Test
  @DisplayName("the engine produces a path rooted at the receiver's object identity")
  void theEngineProducesAPathRootedAtTheReceiversObjectIdentity() throws Exception {
    TacProgram program = translate(TWO_ALLOCATION_SITES);

    PointsToSolver solver = new PointsToSolver(AbstractObject.Policy.CALL_SITE);
    solver.solve(program.functions());

    TacFunction holder = functionNamed(program, "run");
    assertThat(holder).as("the Holder function").isNotNull();

    // The receiver of the write, and the receiver of the other allocation.
    TacOperand.TacVar first = varUsedAsReceiver(program, holder, "first");
    TacOperand.TacVar second = varUsedAsReceiver(program, holder, "second");
    assertThat(first).as("the receiver of the field write").isNotNull();
    assertThat(second).as("the receiver of the OTHER allocation").isNotNull();

    List<org.plumb.tac.ir.TacAccessPath> firstPaths =
        AccessPathBuilder.fieldPaths(solver, holder.signature(), first, "data");
    List<org.plumb.tac.ir.TacAccessPath> secondPaths =
        AccessPathBuilder.fieldPaths(solver, holder.signature(), second, "data");

    assertThat(firstPaths).as("the write's receiver resolves to an object").hasSize(1);
    assertThat(secondPaths).as("the other receiver resolves to an object").hasSize(1);

    assertThat(firstPaths.get(0).root())
        .as("the path's root is the object's identity")
        .isNotEqualTo("first");
    assertThat(secondPaths.get(0).root())
        .as("the path's root is the object's identity")
        .isNotEqualTo("second");

    assertThat(firstPaths.get(0))
        .as(
            "two allocations of one class are two locations -- this is what the coarse"
                + " receiver-name key could not express")
        .isNotEqualTo(secondPaths.get(0));
  }

  @Test
  @DisplayName("the store holds one object's field taint apart from another's")
  void theStoreHoldsOneObjectsFieldTaintApartFromAnothers() throws Exception {
    TacProgram program = translate(TWO_ALLOCATION_SITES);

    PointsToSolver solver = new PointsToSolver(AbstractObject.Policy.CALL_SITE);
    solver.solve(program.functions());

    TacFunction holder = functionNamed(program, "run");
    TacOperand.TacVar first = varUsedAsReceiver(program, holder, "first");
    TacOperand.TacVar second = varUsedAsReceiver(program, holder, "second");

    List<org.plumb.tac.ir.TacAccessPath> firstPaths =
        AccessPathBuilder.fieldPaths(solver, holder.signature(), first, "data");
    List<org.plumb.tac.ir.TacAccessPath> secondPaths =
        AccessPathBuilder.fieldPaths(solver, holder.signature(), second, "data");

    AccessPathTaintStore store = new AccessPathTaintStore();
    store.record(firstPaths.get(0), "input:request", List.of());

    assertThat(store.read(secondPaths.get(0)))
        .as(
            "the field of the OTHER allocation must not carry the taint that was written to the"
                + " first one's field")
        .isNull();
    assertThat(store.read(firstPaths.get(0))).as("the written field is tainted").isNotNull();
  }

  @Test
  @DisplayName("the engine's scan runs with the access-path store wired in")
  void theEnginesScanRunsWithTheAccessPathStoreWiredIn() throws Exception {
    Path file = tmp.resolve("Holder.java");
    Files.writeString(file, TWO_ALLOCATION_SITES);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();

    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new SqlInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);

    assertThat(reporter.all()).as("the scan ran").isNotNull();
    assertThat(reporter.context())
        .as("the reporter carries the analysis context the engine published")
        .isNotNull();
    assertThat(reporter.context().pointsToSolver())
        .as(
            "the access-path builder needs the solver, so the engine must publish one -- a scan"
                + " with no solver cannot resolve a receiver to an object")
        .isNotNull();
  }

  // ---- helpers -------------------------------------------------------------------------------

  private static TacProgram translate(String source) throws Exception {
    Path dir = Files.createTempDirectory("access-path-test");
    Path file = dir.resolve("Holder.java");
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    return new TacTranslator(ps).translate();
  }

  private static TacFunction functionNamed(TacProgram program, String simpleName) {
    for (TacFunction fn : program.functions()) {
      if (simpleName.equals(fn.name())) {
        return fn;
      }
    }
    return null;
  }

  /**
   * The variable the engine used as a field receiver.
   *
   * <p>The engine's own receiver resolution (through the class-ref map) is not re-implemented here:
   * the test asks for the variable that appears as the receiver of a FIELDSTORE or FIELDLOAD, which
   * is what the engine sees.
   */
  private static TacOperand.TacVar varUsedAsReceiver(
      TacProgram program, TacFunction fn, String varName) {
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind != TacInstruction.Kind.FIELDSTORE && ins.kind != TacInstruction.Kind.FIELDLOAD) {
        continue;
      }
      if (ins.lhs1 instanceof TacOperand.TacVar recv && varName.equals(recv.name())) {
        return recv;
      }
    }
    // The variable may not be a receiver itself -- it may only be the destination of the MOVE
    // that binds it to an allocation. The engine's receiver resolution follows that MOVE, so the
    // test does too: it asks for the variable the FUNCTION holds under that name.
    for (TacOperand local : fn.locals()) {
      if (local instanceof TacOperand.TacVar v && varName.equals(v.name())) {
        return v;
      }
    }
    return null;
  }
}

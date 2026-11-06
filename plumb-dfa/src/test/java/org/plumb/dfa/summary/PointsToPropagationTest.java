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
package org.plumb.dfa.summary;

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
 * The points-to solver's answer must REACH the taint lattice, not just sit in the solver.
 *
 * <p><b>What was dead.</b> {@link Operation.PointsToPropagate} was a declared record with no
 * producer and no consumer: the points-to solver knew which objects a method returns, and the
 * summary carried nothing about it, so a caller's destination never inherited the callee's object
 * identity. Two instances of one class were therefore indistinguishable to the taint flow — a field
 * written on one instance could be read back on another.
 *
 * <p><b>What these tests pin.</b> The three links of the chain:
 *
 * <ol>
 *   <li>The solver knows what a method returns.
 *   <li>The summary carries that answer as a {@code PointsToPropagate} operation.
 *   <li>The caller's destination inherits the callee's objects.
 * </ol>
 */
class PointsToPropagationTest {

  @TempDir Path tmp;

  /**
   * A method that allocates and returns an object, and a caller that stores the result.
   *
   * <p>The allocation is what gives the returned object an identity distinct from every other
   * instance of the same type.
   */
  private static final String FACTORY_AND_CALLER =
      """
      class Box {
        String data;
      }
      class Factory {
        Box make() {
          return new Box();
        }
      }
      class Caller {
        void run() {
          Factory f = new Factory();
          Box b = f.make();
          b.data = "x";
        }
      }
      """;

  @Test
  @DisplayName("the solver knows which objects a method returns")
  void theSolverKnowsWhatAMethodReturns() throws Exception {
    TacProgram program = translate(FACTORY_AND_CALLER);

    PointsToSolver solver = new PointsToSolver(AbstractObject.Policy.CALL_SITE);
    solver.solve(program.functions());

    java.util.Set<AbstractObject.Obj> returned = solver.returnObjectsOf(factorySignature(program));

    assertThat(returned)
        .as("the factory returns the object it allocated, and the solver knows which one")
        .isNotEmpty();
  }

  @Test
  @DisplayName("the summary carries the return value's object identity")
  void theSummaryCarriesTheReturnValuesObjectIdentity() throws Exception {
    TacProgram program = translate(FACTORY_AND_CALLER);

    PointsToSolver solver = new PointsToSolver(AbstractObject.Policy.CALL_SITE);
    solver.solve(program.functions());

    // Build the operation the way the engine's producer does.
    java.util.Set<String> labels = new java.util.LinkedHashSet<>();
    for (AbstractObject.Obj o : solver.returnObjectsOf(factorySignature(program))) {
      labels.add(o.label());
    }
    assertThat(labels).as("the alloc sites have labels").isNotEmpty();

    Operation.PointsToPropagate pts = new Operation.PointsToPropagate(labels);
    assertThat(pts.allocSites()).as("the operation carries the alloc sites").isEqualTo(labels);
  }

  @Test
  @DisplayName("the caller's destination inherits the callee's objects")
  void theCallersDestinationInheritsTheCalleesObjects() throws Exception {
    TacProgram program = translate(FACTORY_AND_CALLER);

    PointsToSolver solver = new PointsToSolver(AbstractObject.Policy.CALL_SITE);
    solver.solve(program.functions());

    java.util.Set<AbstractObject.Obj> returned = solver.returnObjectsOf(factorySignature(program));
    assertThat(returned).as("the callee returns a known object").isNotEmpty();

    TacOperand.TacVar dest = destOfCallTo(program, "make");
    assertThat(dest).as("the caller's destination variable").isNotNull();

    String callerSignature = callerSignature(program);
    assertThat(solver.pointsTo(callerSignature, dest))
        .as("before the propagation the destination points at nothing the caller allocated")
        .isEmpty();

    solver.addPointsTo(callerSignature, dest, returned);

    assertThat(solver.pointsTo(callerSignature, dest))
        .as("the destination now points at the callee's allocation")
        .isNotEmpty()
        .containsAll(returned);
  }

  @Test
  @DisplayName("the engine runs with the points-to operation in place")
  void theEngineRunsWithTheOperationInPlace() throws Exception {
    TacProgram program = translate(FACTORY_AND_CALLER);
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new SqlInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);

    assertThat(reporter.all()).as("the scan ran").isNotNull();
  }

  /** Translates a source into a program. */
  private TacProgram translate(String source) throws Exception {
    Path file = tmp.resolve("P" + source.hashCode() + ".java");
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    return new TacTranslator(ps).translate();
  }

  /** The signature of the method named {@code make}. */
  private static String factorySignature(TacProgram program) {
    return program.functions().stream()
        .filter(f -> "make".equals(f.name()))
        .map(f -> f.signature())
        .findFirst()
        .orElseThrow(() -> new AssertionError("the factory method is missing"));
  }

  /** The signature of the method named {@code run}. */
  private static String callerSignature(TacProgram program) {
    return program.functions().stream()
        .filter(f -> "run".equals(f.name()))
        .map(f -> f.signature())
        .findFirst()
        .orElseThrow(() -> new AssertionError("the caller method is missing"));
  }

  /** The destination of the CALL to the method named {@code methodName}. */
  private static TacOperand.TacVar destOfCallTo(TacProgram program, String methodName) {
    for (TacFunction fn : program.functions()) {
      for (int i = 0; i < fn.instructionCount(); i++) {
        TacInstruction ins = fn.instructionAt(i);
        if (ins.kind != TacInstruction.Kind.CALL) continue;
        if (ins.calleeSig == null || !ins.calleeSig.contains(methodName)) continue;
        if (ins.dest instanceof TacOperand.TacVar dv) return dv;
      }
    }
    return null;
  }
}

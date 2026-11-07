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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.points.AbstractObject;
import org.plumb.dfa.points.AbstractObject.Context;
import org.plumb.dfa.points.AbstractObject.Policy;
import org.plumb.dfa.points.PointsToSolver;
import org.plumb.tac.ir.TacAccessPath;
import org.plumb.tac.ir.TacOperand;

/**
 * The access-path builder turns a receiver into the LOCATIONS it reaches.
 *
 * <p><b>Why this test exists.</b> A builder that returned the receiver's NAME would produce a path
 * that looks like an access path and carries none of the information that makes one useful. The
 * root has to be the object's identity, so that two allocations of one class are two locations.
 */
@DisplayName("Taint: the access-path builder resolves a receiver to its objects")
class AccessPathBuilderTest {

  private static final String SIG = "com.example.Factory#make";

  /** Two allocations of the same class: the same TYPE, different OBJECTS. */
  private static AbstractObject.Obj alloc(int index) {
    return AbstractObject.Obj.at(
        "com.example.Factory#make", index, "com.example.Box", Context.empty());
  }

  private static TacOperand.TacVar var(String name) {
    return new TacOperand.TacVar(name, null, true);
  }

  @Test
  @DisplayName("a receiver with no object information yields no path")
  void aReceiverWithNoObjectInformationYieldsNoPath() {
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);

    List<TacAccessPath> paths = AccessPathBuilder.fieldPaths(solver, SIG, var("h"), "data");

    assertThat(paths)
        .as(
            "an unknown receiver must produce NO path, so the caller keeps the coarse behaviour --"
                + " a fabricated path would claim a location the engine cannot justify")
        .isEmpty();
  }

  @Test
  @DisplayName("a null solver yields no path")
  void aNullSolverYieldsNoPath() {
    List<TacAccessPath> paths = AccessPathBuilder.fieldPaths(null, SIG, var("h"), "data");

    assertThat(paths).as("no solver, no object information, no path").isEmpty();
  }

  @Test
  @DisplayName("the path's root is the object's identity, not the variable's name")
  void thePathsRootIsTheObjectsIdentityNotTheVariablesName() {
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    AbstractObject.Obj obj = alloc(12);
    solver.addPointsTo(SIG, var("h"), List.of(obj));

    List<TacAccessPath> paths = AccessPathBuilder.fieldPaths(solver, SIG, var("h"), "data");

    assertThat(paths).hasSize(1);
    TacAccessPath path = paths.get(0);

    assertThat(path.root()).as("the root must be the object's identity").isEqualTo(obj.label());
    assertThat(path.root())
        .as("the root must NOT be the variable's name -- that is the whole point")
        .isNotEqualTo("h");
    assertThat(path.depth()).isEqualTo(1);
  }

  @Test
  @DisplayName("two allocations of one class are two different paths")
  void twoAllocationsOfOneClassAreTwoDifferentPaths() {
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    AbstractObject.Obj a = alloc(12);
    AbstractObject.Obj b = alloc(27);
    solver.addPointsTo(SIG, var("h"), List.of(a, b));

    List<TacAccessPath> paths = AccessPathBuilder.fieldPaths(solver, SIG, var("h"), "data");

    assertThat(paths).as("a receiver that may hold two objects has two field locations").hasSize(2);
    assertThat(paths.get(0)).isNotEqualTo(paths.get(1));
    assertThat(paths).contains(TacAccessPath.root(a.label()).withField("data"));
    assertThat(paths).contains(TacAccessPath.root(b.label()).withField("data"));
  }

  @Test
  @DisplayName("two receivers holding different objects get different paths")
  void twoReceiversHoldingDifferentObjectsGetDifferentPaths() {
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    AbstractObject.Obj a = alloc(12);
    AbstractObject.Obj b = alloc(27);
    solver.addPointsTo(SIG, var("h"), List.of(a));
    solver.addPointsTo(SIG, var("k"), List.of(b));

    List<TacAccessPath> pathH = AccessPathBuilder.fieldPaths(solver, SIG, var("h"), "data");
    List<TacAccessPath> pathK = AccessPathBuilder.fieldPaths(solver, SIG, var("k"), "data");

    assertThat(pathH).hasSize(1);
    assertThat(pathK).hasSize(1);
    assertThat(pathH.get(0))
        .as(
            "two objects reached through two different variables are two different locations --"
                + " and the paths say so")
        .isNotEqualTo(pathK.get(0));
  }

  @Test
  @DisplayName("an element path carries the index")
  void anElementPathCarriesTheIndex() {
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    AbstractObject.Obj obj = alloc(12);
    solver.addPointsTo(SIG, var("a"), List.of(obj));

    List<TacAccessPath> paths = AccessPathBuilder.elementPaths(solver, SIG, var("a"), "0");

    assertThat(paths).hasSize(1);
    assertThat(paths.get(0)).isEqualTo(TacAccessPath.root(obj.label()).withArrayIndex("0"));
  }

  @Test
  @DisplayName("a non-variable receiver yields no path")
  void aNonVariableReceiverYieldsNoPath() {
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);

    List<TacAccessPath> paths =
        AccessPathBuilder.fieldPaths(solver, SIG, TacOperand.Const.ofString("literal"), "data");

    assertThat(paths).as("a constant is not a receiver, so it names no location").isEmpty();
  }

  @Test
  @DisplayName("an empty field name yields no path")
  void anEmptyFieldNameYieldsNoPath() {
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    AbstractObject.Obj obj = alloc(12);
    solver.addPointsTo(SIG, var("h"), List.of(obj));

    List<TacAccessPath> paths = AccessPathBuilder.fieldPaths(solver, SIG, var("h"), "");

    assertThat(paths).isEmpty();
  }
}

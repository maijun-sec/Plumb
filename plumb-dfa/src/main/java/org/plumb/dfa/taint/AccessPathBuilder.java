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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.plumb.dfa.points.AbstractObject;
import org.plumb.dfa.points.PointsToSolver;
import org.plumb.tac.ir.TacAccessPath;
import org.plumb.tac.ir.TacOperand;

/**
 * Builds the access path of a location, from the receiver that reaches it.
 *
 * <p><b>Why this class exists.</b> The engine tracks field taint by a flat {@code
 * "receiver.fieldName"} string, and the receiver is a VARIABLE NAME. Two different objects reached
 * through the same name therefore share one slot. The access-path vocabulary ({@link
 * TacAccessPath}) exists to name a LOCATION instead, and this class is the piece that produces
 * those names: it asks the points-to solver which objects a receiver may hold, and turns each of
 * them into a path root.
 *
 * <p><b>Why the root is an allocation identity.</b> A path's root has to distinguish two objects of
 * the same type. The solver's object label ({@code type@site:context}) does exactly that: the
 * allocation site is what makes one object a different object from another allocation of the same
 * class. A variable name cannot do this, because the same variable name is reused for different
 * objects across a function, and a different variable name may hold the same object.
 *
 * <p><b>What happens when the receiver's object is unknown.</b> The builder returns an EMPTY list,
 * and the caller keeps the coarse, name-keyed behaviour. That is the conservative direction: a
 * coarse key can over-report (two objects share a slot), while a missing path can under-report (the
 * location is not tracked at all). When the engine cannot prove which object a receiver holds,
 * over-reporting is the safer of the two.
 *
 * <p><b>Why the result may hold several paths.</b> A receiver may point at more than one object,
 * and then the field is a field of each of them. All of those locations can carry the taint, so all
 * of them have to be recorded.
 */
public final class AccessPathBuilder {

  private AccessPathBuilder() {}

  /**
   * The access paths of {@code receiver.fieldName}, one per object the receiver may hold.
   *
   * @param solver the points-to solver; {@code null} means "no object information"
   * @param signature the enclosing function's signature
   * @param receiver the variable holding the object
   * @param fieldName the field being accessed
   * @return the paths, or an empty list when the receiver's object is not known
   */
  public static List<TacAccessPath> fieldPaths(
      PointsToSolver solver, String signature, TacOperand receiver, String fieldName) {
    if (solver == null || signature == null || fieldName == null || fieldName.isEmpty()) {
      return List.of();
    }
    if (!(receiver instanceof TacOperand.TacVar recv)) {
      return List.of();
    }
    Set<AbstractObject.Obj> objects;
    try {
      objects = solver.pointsToAcrossContexts(signature, recv);
    } catch (RuntimeException e) {
      // A points-to query that fails costs the refinement, not the analysis: the caller
      // falls back to the coarse, name-keyed behaviour.
      return List.of();
    }
    if (objects == null || objects.isEmpty()) {
      return List.of();
    }
    Set<TacAccessPath> paths = new LinkedHashSet<>();
    for (AbstractObject.Obj o : objects) {
      if (o == null) continue;
      String root;
      try {
        root = o.label();
      } catch (RuntimeException e) {
        continue;
      }
      if (root == null || root.isEmpty()) continue;
      paths.add(TacAccessPath.root(root).withField(fieldName));
    }
    return new ArrayList<>(paths);
  }

  /**
   * The access paths of {@code receiver[index]}, one per object the receiver may hold.
   *
   * @param solver the points-to solver; {@code null} means "no object information"
   * @param signature the enclosing function's signature
   * @param receiver the variable holding the array
   * @param index the index expression's textual form
   * @return the paths, or an empty list when the receiver's object is not known
   */
  public static List<TacAccessPath> elementPaths(
      PointsToSolver solver, String signature, TacOperand receiver, String index) {
    if (solver == null || signature == null || index == null) {
      return List.of();
    }
    if (!(receiver instanceof TacOperand.TacVar recv)) {
      return List.of();
    }
    Set<AbstractObject.Obj> objects;
    try {
      objects = solver.pointsToAcrossContexts(signature, recv);
    } catch (RuntimeException e) {
      return List.of();
    }
    if (objects == null || objects.isEmpty()) {
      return List.of();
    }
    Set<TacAccessPath> paths = new LinkedHashSet<>();
    for (AbstractObject.Obj o : objects) {
      if (o == null) continue;
      String root;
      try {
        root = o.label();
      } catch (RuntimeException e) {
        continue;
      }
      if (root == null || root.isEmpty()) continue;
      paths.add(TacAccessPath.root(root).withArrayIndex(index));
    }
    return new ArrayList<>(paths);
  }
}

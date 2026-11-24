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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.plumb.dfa.cg.FunctionSignature;

/**
 * A method's inter-procedural summary, computed once from the method's TAC IR and consulted by the
 * engine at every call site that targets this method.
 *
 * <p>The summary has two parts:
 *
 * <ul>
 *   <li>{@link #returnShape} -- what the return value looks like (see {@link ReturnShape}). This is
 *       what the engine uses to fold the call into a constant or parameter reference.
 *   <li>{@link #sideEffect} -- what state changes the method makes (see {@link SideEffect}). This
 *       is what the engine uses to invalidate caller-side read state.
 * </ul>
 *
 * <p>A summary is keyed by the callee's {@link FunctionSignature}. The {@link MethodSummaryCache}
 * holds one summary per key. A summary is immutable; once built it is safe to share across threads.
 */
public final class MethodSummary {

  public final FunctionSignature signature;
  public final ReturnShape returnShape;
  public final SideEffect sideEffect;

  /**
   * The shapes of the results PAST the first.
   *
   * <p>A call that produces several values (Go's {@code v, err := f()}) binds each of them to a
   * variable of its own, and the caller has to know what each one holds -- the same question {@link
   * #returnShape} answers for the first result.
   *
   * <p>The list is indexed by result slot: entry 0 is the SECOND result. It is empty when the
   * method produces a single result, which is the ordinary case.
   */
  public final List<ReturnShape> extraResultShapes;

  public MethodSummary(
      FunctionSignature signature, ReturnShape returnShape, SideEffect sideEffect) {
    this(signature, returnShape, sideEffect, List.of());
  }

  public MethodSummary(
      FunctionSignature signature,
      ReturnShape returnShape,
      SideEffect sideEffect,
      List<ReturnShape> extraResultShapes) {
    this.signature = Objects.requireNonNull(signature);
    this.returnShape = Objects.requireNonNull(returnShape);
    this.sideEffect = sideEffect == null ? SideEffect.pure() : sideEffect;
    this.extraResultShapes =
        extraResultShapes == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(extraResultShapes));
  }

  public boolean isPure() {
    return sideEffect.isPure();
  }

  public boolean isUnknown() {
    return returnShape.kind == ReturnShape.Kind.UNKNOWN;
  }

  @Override
  public String toString() {
    return "MethodSummary{"
        + signature
        + " -> "
        + returnShape
        + (sideEffect.isPure() ? ", pure" : ", sideEffect=" + sideEffect)
        + "}";
  }
}

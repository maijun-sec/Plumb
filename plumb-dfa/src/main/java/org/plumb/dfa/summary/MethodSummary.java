package org.plumb.dfa.summary;

import java.util.Objects;
import org.plumb.dfa.cg.FunctionSignature;

/**
 * A method's inter-procedural summary, computed once from the method's TAC IR and consulted by the
 * engine at every call site that targets this method.
 *
 * <p>The summary has two parts:
 *
 * <ul>
 *   <li>{@link #returnShape} ? what the return value looks like (see {@link ReturnShape}). This is
 *       what the engine uses to fold the call into a constant or parameter reference.
 *   <li>{@link #sideEffect} ? what state changes the method makes (see {@link SideEffect}). This is
 *       what the engine uses to invalidate caller-side read state.
 * </ul>
 *
 * <p>A summary is keyed by the callee's {@link FunctionSignature}. The {@link MethodSummaryCache}
 * holds one summary per key. A summary is immutable; once built it is safe to share across threads.
 */
public final class MethodSummary {

  public final FunctionSignature signature;
  public final ReturnShape returnShape;
  public final SideEffect sideEffect;

  public MethodSummary(
      FunctionSignature signature, ReturnShape returnShape, SideEffect sideEffect) {
    this.signature = Objects.requireNonNull(signature);
    this.returnShape = Objects.requireNonNull(returnShape);
    this.sideEffect = sideEffect == null ? SideEffect.pure() : sideEffect;
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

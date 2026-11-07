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

import java.util.Objects;

/**
 * The identity of a summary entry: WHICH function, and WHICH call context it was reached through.
 *
 * <p><b>Why the signature alone is not enough.</b> A summary says "this function does these things
 * to its parameters". Which parameters are involved depends on the call site: a helper called with
 * a tainted argument propagates taint, and the same helper called with a clean argument does not.
 * When every caller shares one summary, the two facts merge, and a call that should be clean
 * inherits the taint of an unrelated call somewhere else in the program.
 *
 * <p><b>Why the call string is the CALL SITE, not the caller.</b> A call string of {@code
 * callerSignature#instructionIndex} distinguishes two calls to the same function from the same
 * caller -- which is the distinction that matters, because two calls in one method are two
 * different places with two different arguments. A call string of just the caller's signature would
 * still merge them.
 *
 * <p>The format is the one {@link org.plumb.dfa.points.PointsToSolver} already builds for its own
 * contexts, so the two context-sensitive mechanisms in the engine speak the same vocabulary. That
 * is deliberate: a reader who has learned one does not have to learn the other.
 *
 * <p><b>Why the empty call string is meaningful.</b> A summary built without a caller in view --
 * the function's own, context-free summary -- is keyed by the EMPTY call string. It is the fallback
 * every context-sensitive lookup falls back to, so a context-sensitive lookup can never find LESS
 * than a context-insensitive one. The empty call string is therefore not "unknown"; it is "the
 * union over all contexts".
 */
public final class SummaryKey {

  /** The call string of a summary that carries no caller context. */
  public static final String NO_CALL_STRING = "";

  private final String signature;
  private final String callString;

  /**
   * A key.
   *
   * @param signature the callee's signature (or any of its alias spellings)
   * @param callString the call context, or {@link #NO_CALL_STRING} for the context-free summary
   */
  public SummaryKey(String signature, String callString) {
    this.signature = signature == null ? "" : signature;
    this.callString = callString == null ? NO_CALL_STRING : callString;
  }

  /**
   * The context-free key for a signature.
   *
   * @param signature the callee's signature
   * @return the key with no call context
   */
  public static SummaryKey of(String signature) {
    return new SummaryKey(signature, NO_CALL_STRING);
  }

  /**
   * The key for a signature reached through a call site.
   *
   * @param signature the callee's signature
   * @param callString the call context
   * @return the key
   */
  public static SummaryKey inContext(String signature, String callString) {
    return new SummaryKey(signature, callString);
  }

  /** The callee's signature (or one of its alias spellings). */
  public String signature() {
    return signature;
  }

  /** The call context, or the empty string for the context-free summary. */
  public String callString() {
    return callString;
  }

  /** Whether this key names the context-free summary. */
  public boolean isContextFree() {
    return callString.isEmpty();
  }

  /**
   * The same signature, with no call context.
   *
   * @return the context-free key
   */
  public SummaryKey withoutCallString() {
    return new SummaryKey(signature, NO_CALL_STRING);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof SummaryKey k)) return false;
    return signature.equals(k.signature) && callString.equals(k.callString);
  }

  @Override
  public int hashCode() {
    return Objects.hash(signature, callString);
  }

  @Override
  public String toString() {
    return callString.isEmpty() ? signature : signature + "@" + callString;
  }
}

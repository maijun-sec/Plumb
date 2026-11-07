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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a caller passed at one call site: which of its arguments were tainted, and which formal
 * parameter of the callee each of them binds to.
 *
 * <p><b>Why this is a separate object rather than a field on the summary.</b> The summary is the
 * CALLEE's statement about itself: "this function does these things to its parameters". The binding
 * is the CALLER's statement about one call: "at this call site, this argument is tainted and it
 * lands on that formal parameter". Conflating the two means the callee's facts get overwritten by
 * one caller's facts, and the next caller reads the first caller's answer.
 *
 * <p><b>Why the binding is recorded per call site rather than computed on the fly.</b> The binding
 * is what makes a context-sensitive lookup meaningful: the summary for {@code (callee, call site)}
 * holds the operations that are reachable GIVEN that call site's tainted arguments. A call site
 * whose tainted argument lands on formal 0 produces a summary whose formal-0 operations are live; a
 * different call site whose tainted argument lands on formal 1 produces a different summary, in
 * which formal 0 is clean.
 *
 * <p><b>Why the receiver is not in the binding.</b> The receiver is {@code args[0]} on an instance
 * call, and it is not a formal parameter in the same sense -- the callee's "formal 0" is its first
 * USER argument. The binding therefore records USER argument indices on both sides, and the
 * receiver is handled separately by the receiver-passthrough rule.
 */
public final class CallSiteBinding {

  /** The binding of a call site with nothing tainted at it. */
  public static final CallSiteBinding NONE = new CallSiteBinding(Map.of());

  private final Map<Integer, Integer> taintedArgToFormal;

  /**
   * A binding.
   *
   * @param taintedArgToFormal maps a tainted USER-argument index to the formal parameter index it
   *     binds to
   */
  private CallSiteBinding(Map<Integer, Integer> taintedArgToFormal) {
    this.taintedArgToFormal =
        taintedArgToFormal == null
            ? Map.of()
            : Collections.unmodifiableMap(new TreeMap<>(taintedArgToFormal));
  }

  /**
   * The tainted user-argument indices at this call site.
   *
   * @return the tainted argument indices
   */
  public Set<Integer> taintedArgIndices() {
    return taintedArgToFormal.keySet();
  }

  /**
   * The formal parameter a tainted user argument binds to.
   *
   * @param argIndex the user argument's index
   * @return the formal parameter index, or {@code -1} when that argument is not tainted
   */
  public int formalOf(int argIndex) {
    Integer formal = taintedArgToFormal.get(argIndex);
    return formal == null ? -1 : formal;
  }

  /**
   * Whether this binding says anything at all.
   *
   * @return true when no argument was tainted at this call site
   */
  public boolean isEmpty() {
    return taintedArgToFormal.isEmpty();
  }

  /**
   * The formal parameters that are tainted at this call site.
   *
   * @return the tainted formal parameter indices
   */
  public Set<Integer> taintedFormals() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(taintedArgToFormal.values()));
  }

  /**
   * Build a binding.
   *
   * @param taintedArgToFormal maps a tainted user-argument index to the formal parameter index it
   *     binds to
   * @return the binding
   */
  public static CallSiteBinding of(Map<Integer, Integer> taintedArgToFormal) {
    return taintedArgToFormal == null || taintedArgToFormal.isEmpty()
        ? NONE
        : new CallSiteBinding(taintedArgToFormal);
  }

  @Override
  public String toString() {
    if (isEmpty()) return "CallSiteBinding(none)";
    StringBuilder sb = new StringBuilder("CallSiteBinding(");
    boolean first = true;
    for (Map.Entry<Integer, Integer> e : taintedArgToFormal.entrySet()) {
      if (!first) sb.append(", ");
      first = false;
      sb.append("arg").append(e.getKey()).append("->param").append(e.getValue());
    }
    return sb.append(")").toString();
  }
}

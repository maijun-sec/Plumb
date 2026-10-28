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
package org.plumb.dfa.context;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-call-site context snapshot. When the DfaEngine processes a CALL instruction it snapshots the
 * caller's current path condition (the union of all guards the cross-checker store holds) and
 * propagates a {@code CallSiteContext} to the callee. The callee's checkers can then consult the
 * context in {@code initFunction} to decide "is this guard already implied by my caller?".
 *
 * <p>The context is immutable per call site: a new instance is created for every CALL instruction
 * the engine processes. Two calls to the same callee from different sites in the same caller get
 * different contexts (different {@link #callerSignature()} + {@link #callSiteLine()} pairs) —
 * that's the 1-CFA sensitivity the new layer is designed to give the analysis.
 */
public final class CallSiteContext {

  private final String callerSignature;
  private final String calleeSignature;
  private final int callSiteLine;

  /** Var name → predicate the caller knew at the call site. */
  private final Map<String, Set<String>> snapshot;

  public CallSiteContext(
      String callerSignature,
      String calleeSignature,
      int callSiteLine,
      Map<String, Set<String>> snapshot) {
    this.callerSignature = callerSignature;
    this.calleeSignature = calleeSignature;
    this.callSiteLine = callSiteLine;
    this.snapshot = new LinkedHashMap<>(snapshot);
  }

  public String callerSignature() {
    return callerSignature;
  }

  public String calleeSignature() {
    return calleeSignature;
  }

  public int callSiteLine() {
    return callSiteLine;
  }

  public Set<String> variables() {
    return snapshot.keySet();
  }

  public Set<String> guardsFor(String var) {
    return snapshot.getOrDefault(var, Set.of());
  }

  public Map<String, Set<String>> snapshot() {
    return snapshot;
  }

  /**
   * The unique key for this call site -- caller signature, callee signature, and the line number of
   * the CALL. Two contexts with the same key represent the same "edge" in the context- sensitive
   * call graph.
   */
  public String key() {
    return callerSignature + " -> " + calleeSignature + " @" + callSiteLine;
  }

  /** Empty context used when no caller is recorded (e.g. an entry-point analysis). */
  public static final CallSiteContext EMPTY = new CallSiteContext("", "", -1, Map.of());

  @Override
  public String toString() {
    return "CallSiteContext{" + key() + ", vars=" + snapshot.size() + "}";
  }

  /**
   * Build a context by snapshotting the current contents of {@link
   * org.plumb.dfa.solver.GuardStore}.
   */
  public static CallSiteContext from(
      String callerSig, String calleeSig, int line, org.plumb.dfa.solver.GuardStore store) {
    Map<String, Set<String>> snap = new LinkedHashMap<>();
    if (store != null) {
      for (String var : store.variables()) {
        List<String> gs = store.guardsFor(var);
        if (!gs.isEmpty()) {
          snap.put(var, new LinkedHashSet<>(gs));
        }
      }
    }
    return new CallSiteContext(
        callerSig == null ? "" : callerSig, calleeSig == null ? "" : calleeSig, line, snap);
  }
}

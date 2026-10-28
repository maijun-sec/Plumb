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
package org.plumb.dfa.cg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-level <i>call-site-sensitive</i> (1-CFA) call-graph strategy.
 *
 * <p>Builds on {@link VtaCallGraphStrategy} (per-function variable points-to sets) by adding one
 * further refinement: a virtual call is resolved only against overrides whose declaring class
 * appears in the <i>caller's</i> union of points-to sets (across all of the caller's variables).
 * The intuition is that a virtual call in {@code caller.m} can only ever dispatch to a type the
 * caller itself has seen -- if the caller never instantiates {@code Cat}, the override {@code
 * Cat#m} cannot fire there even if the receiver's declared type would let it.
 *
 * <p>This is a coarse approximation of 1-CFA: a full 1-CFA would track the call stack at every call
 * site and instantiate a fresh function summary per (caller, callee) pair. Here we only narrow the
 * override set at build time; the summary key remains the callee signature. The win is a tighter
 * call graph at the same per-function cost; the cost is the union over the caller's points-to set,
 * which is one extra pass per function.
 *
 * <p>Note: the strategy intentionally does <b>not</b> track per-callsite context inside a single
 * caller -- if two different call sites in the same caller resolve to disjoint override sets, the
 * join at the caller's points-to union will still keep both. That's the standard 0-CFA-vs-1-CFA
 * trade-off; the implementation is closer to 0-CFA but with a callee-side "what does the caller
 * know" filter.
 */
public final class OneCfaCallGraphStrategy implements CallGraphStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(OneCfaCallGraphStrategy.class);

  private final TacProgram program;
  private final boolean includeLibraries;

  public OneCfaCallGraphStrategy(TacProgram program) {
    this(program, false);
  }

  public OneCfaCallGraphStrategy(TacProgram program, boolean includeLibraries) {
    this.program = program;
    this.includeLibraries = includeLibraries;
  }

  @Override
  public String name() {
    return "1cfa";
  }

  @Override
  public CallGraph build() {
    // 1. Per-function points-to
    //    (same algorithm as
    //    VTA). Re-use the
    //    VTA helper to keep
    //    one source of truth.
    Map<String, Map<TacOperand.TacVar, Set<String>>> pointsTo =
        VtaCallGraphStrategy.computePointsTo(program);

    // 2. Per-function union of
    //    all points-to sets.
    //    This is the caller's
    //    "what types have I
    //    ever seen" set.
    Map<String, Set<String>> callerSeenTypes = new HashMap<>();
    for (TacFunction fn : program.functions()) {
      Set<String> seen = new LinkedHashSet<>();
      for (Set<String> pts : pointsTo.getOrDefault(fn.signature(), Map.of()).values()) {
        seen.addAll(pts);
      }
      callerSeenTypes.put(fn.signature(), seen);
    }

    // 3. Index overrides by
    //    method+arity.
    Map<String, List<TacFunction>> bySimpleName = indexBySimpleName(program);

    // 4. Build the graph.
    Set<FunctionNode> nodes = new LinkedHashSet<>();
    Map<FunctionNode, List<CallSite>> forward = new HashMap<>();
    Map<FunctionNode, List<CallSite>> reverse = new HashMap<>();
    // Build the bare-name →
    // canonical sig map.
    java.util.Map<String, java.util.List<String>> bareNameToCanonical =
        CallGraphBuilder.buildBareNameMap(program);
    for (TacFunction fn : program.functions()) {
      FunctionNode caller = new FunctionNode(fn.signature());
      nodes.add(caller);
      forward.computeIfAbsent(caller, k -> new ArrayList<>());
      Map<TacOperand.TacVar, Set<String>> fnPts = pointsTo.getOrDefault(fn.signature(), Map.of());
      Set<String> callerSeen = callerSeenTypes.getOrDefault(fn.signature(), Set.of());
      for (int i = 0; i < fn.instructions().size(); i++) {
        TacInstruction ins = fn.instructionAt(i);
        if (ins.kind != TacInstruction.Kind.CALL) continue;
        String calleeSig = ins.calleeSig;
        if (calleeSig == null || calleeSig.isEmpty()) continue;
        if (!includeLibraries && isLibrary(calleeSig)) continue;
        // Resolve bare-name
        // calleeSig to canonical
        // form with arg-type-aware
        // overload disambiguation.
        String nodeSig =
            CallGraphBuilder.r48ResolveCalleeSigWithArgs(
                program, calleeSig, bareNameToCanonical, ins.args);
        // Direct call: always emit.
        emitCall(caller, nodeSig, ins, nodes, forward, reverse);
        // Virtual call: 1-CFA
        // narrows the override
        // set to overrides
        // whose class is
        // actually seen
        // somewhere in the
        // caller.
        TacOperand receiver = ins.args.isEmpty() ? null : ins.args.get(0);
        Set<String> receiverTypes =
            receiver instanceof TacOperand.TacVar rv ? fnPts.getOrDefault(rv, Set.of()) : Set.of();
        if (receiverTypes.isEmpty()) continue;
        FunctionSignature fs = FunctionSignature.parse(calleeSig);
        if (fs == null) continue;
        String key = fs.method() + "/" + fs.paramTypes().size();
        for (TacFunction override : bySimpleName.getOrDefault(key, List.of())) {
          if (override.signature().equals(calleeSig)) continue;
          String overrideClass = overrideClassName(override.signature());
          if (overrideClass == null) continue;
          // Standard VTA filter:
          // receiver's points-to
          // set intersects
          // override class.
          if (!receiverTypes.contains(overrideClass) && !receiverTypes.contains("Object")) {
            continue;
          }
          // 1-CFA refinement: the
          // caller must have
          // actually seen the
          // override class
          // somewhere. This
          // filters overrides
          // that the receiver
          // could hold but the
          // caller never
          // instantiates.
          if (!callerSeen.contains(overrideClass) && !callerSeen.contains("Object")) {
            continue;
          }
          emitCall(caller, override.signature(), ins, nodes, forward, reverse);
        }
      }
    }
    CallGraph cg = CallGraphBuilder.buildFromMaps(nodes, forward, reverse);
    LOG.info("1-CFA call graph built: {} nodes, {} call sites", nodes.size(), sumSizes(forward));
    return cg;
  }

  // ---- helpers shared with VTA / RTA ----

  private static Map<String, List<TacFunction>> indexBySimpleName(TacProgram program) {
    Map<String, List<TacFunction>> out = new HashMap<>();
    for (TacFunction fn : program.functions()) {
      FunctionSignature fs = FunctionSignature.parse(fn.signature());
      if (fs == null) continue;
      String key = fs.method() + "/" + fs.paramTypes().size();
      out.computeIfAbsent(key, k -> new ArrayList<>()).add(fn);
    }
    return out;
  }

  private static void emitCall(
      FunctionNode caller,
      String calleeSig,
      TacInstruction ins,
      Set<FunctionNode> nodes,
      Map<FunctionNode, List<CallSite>> forward,
      Map<FunctionNode, List<CallSite>> reverse) {
    FunctionNode callee = new FunctionNode(calleeSig);
    nodes.add(callee);
    CallSite.Kind kind = isLibrary(calleeSig) ? CallSite.Kind.LIBRARY : CallSite.Kind.VIRTUAL;
    CallSite cs = new CallSite(caller, callee, -1, ins.line, kind);
    forward.get(caller).add(cs);
    reverse.computeIfAbsent(callee, k -> new ArrayList<>()).add(cs);
  }

  private static boolean isLibrary(String sig) {
    if (sig == null) return false;
    return sig.startsWith("java.")
        || sig.startsWith("javax.")
        || sig.startsWith("org.")
        || sig.startsWith("com.")
        || sig.startsWith("sun.")
        || sig.startsWith("jdk.");
  }

  private static String overrideClassName(String sig) {
    int hashAt = sig.indexOf('#');
    if (hashAt <= 0) return null;
    return sig.substring(0, hashAt);
  }

  private static int sumSizes(Map<FunctionNode, List<CallSite>> forward) {
    int sum = 0;
    for (List<CallSite> l : forward.values()) sum += l.size();
    return sum;
  }
}

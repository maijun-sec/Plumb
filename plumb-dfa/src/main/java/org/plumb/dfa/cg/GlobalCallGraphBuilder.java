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
import java.util.LinkedHashMap;
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
 * Build a {@link CallGraph} from a {@link GlobalTACStore} (which can span multiple TUs).
 *
 * <p>Mirror of Fathom's {@code org.fathom.dfa.cg.GlobalCallGraphBuilder}.
 *
 * <h2>Why this is needed</h2>
 *
 * <p>The TU-local {@link CallGraphBuilder} operates on a single {@link TacProgram} (one TU = one
 * .java file). That model breaks down for any program that has more than one source file: a call
 * from {@code com.example.A.m} to {@code com.example.B.n} (where {@code B} lives in a different
 * .java file) is an unresolved callee in the TU-local graph, so the dataflow walk cannot follow it
 * across the compilation-unit boundary.
 *
 * <h2>Construction</h2>
 *
 * <ol>
 *   <li>Walk every function registered in the {@link GlobalTACStore} (across all TUs). For each
 *       {@link TacInstruction.Kind#CALL} emit one {@link CallSite}.
 *   <li>Resolve the callee signature. A signature that is present in the store is a real call
 *       target; a signature that is missing stays as a placeholder node (so the dataflow walk can
 *       still report "callee not modelled" without crashing).
 *   <li>Hand the call-site list to {@link CallGraph}, which computes RPO / entry points / stats in
 *       one pass.
 * </ol>
 *
 * <h2>Library filtering</h2>
 *
 * <p>Library calls (e.g. {@code java.lang.String#length}) are filtered out by default; pass {@link
 * #withIncludeLibraries(boolean)} to retain them. The filter is a prefix check against the
 * well-known {@code java.*}/{@code javax.*}/{@code com.sun.*} packages.
 *
 * <h2>Inner-class signatures</h2>
 *
 * <p>Functions defined inside a Java inner class have signatures like {@code
 * Outer$Inner#compute(int):int}. The {@link GlobalTACStore} registers them under the same string.
 * Call sites that reference them carry the same string in {@code calleeSig}, so resolution works
 * transparently : no special-casing is needed.
 */
public final class GlobalCallGraphBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalCallGraphBuilder.class);

  private final GlobalTACStore store;
  private boolean includeLibraries = false;

  public GlobalCallGraphBuilder(GlobalTACStore store) {
    this.store = store;
  }

  public GlobalCallGraphBuilder withIncludeLibraries(boolean v) {
    this.includeLibraries = v;
    return this;
  }

  /**
   * Build a {@link CallGraph} over every function registered in the store. The store should be
   * {@link GlobalTACStore#freeze() frozen} before calling this method so the iteration is stable.
   */
  public CallGraph build() {
    Set<FunctionNode> nodes = new LinkedHashSet<>();
    Map<FunctionNode, List<CallSite>> forward = new LinkedHashMap<>();
    Map<FunctionNode, List<CallSite>> reverse = new LinkedHashMap<>();
    // Build the bare-name :         // canonical sig map across
    // every function in the
    // store. The Global store may
    // contain functions from
    // multiple TUs / programs; the
    // map is built once across all
    // of them so a bare-name
    // {@code calleeSig} from a
    // call in TU A can resolve to
    // the canonical sig of a
    // function in TU B.
    //
    // The "global" lookup differs
    // from the per-program
    // {@code CallGraphBuilder.resolveCalleeSigStatic}
    // helper used by CHA / RTA /
    // VTA / OneCFA : those
    // strategies have a single
    // {@link TacProgram} to check
    // "is the raw calleeSig a
    // real function in the
    // program", and they do that
    // check via
    // {@code program.function(sig)}.
    // For Global we use
    // {@link GlobalTACStore#functionBySignature}
    // for the same purpose: it
    // returns a hit if any TU
    // registered a function with
    // that exact sig. The original
    // design looked up the caller's
    // program via
    // {@code TacFunction.origin()}
    // but {@code origin()} only
    // returns the {@link
    // org.plumb.tac.ir.SourceOrigin}
    // enum (SOURCE / LIBRARY /
    // SYSTEM_LIBRARY), not a
    // {@link TacProgram} reference.
    // The cleanest fix is to drop
    // the per-caller program
    // resolution entirely and let
    // the global map + the
    // store-wide sig lookup do the
    // work : this is sound because
    // the global map already
    // covers every function
    // regardless of which TU it
    // came from.
    java.util.Map<String, java.util.List<String>> bareNameToCanonical = new HashMap<>();
    for (TacFunction fn : store.allFunctions()) {
      String bare = CallGraphBuilder.bareNameAndArity(fn.signature());
      if (bare != null) {
        bareNameToCanonical.computeIfAbsent(bare, k -> new ArrayList<>()).add(fn.signature());
      }
    }

    for (TacFunction fn : store.allFunctions()) {
      // use the MergedProgram's merged key
      // (enclosingClass :: signature) as the FunctionNode
      // id so {@code program.function(nodeSig)} in
      // {@link org.plumb.dfa.engine.DfaEngine#orderedFunctions}
      // resolves to the same TacFunction. Previously the
      // bare signature was used, but MergedProgram indexes
      // by merged key : the lookup returned null for every
      // cross-method / cross-file Juliet CWE789/400 variant
      // (V21/V22/V42/V45/V52/V66-V75/V81), silently dropping
      // the worklist's bottom-up analysis order so callees
      // (e.g. {@code bad_source()}) ran AFTER their callers
      // (e.g. {@code bad()}) and the cross-function
      // summary-fold missed the taint propagation.
      String callerMk = mergedKey(fn);
      FunctionNode caller = new FunctionNode(callerMk);
      nodes.add(caller);
      forward.computeIfAbsent(caller, k -> new ArrayList<>());

      int callCount = 0;
      List<TacInstruction> insns = fn.instructions();
      for (TacInstruction insn : insns) if (insn.kind == TacInstruction.Kind.CALL) callCount++;
      // ( dbg instrumentation removed before bulk re-scan)
      for (int i = 0; i < insns.size(); i++) {
        TacInstruction ins = insns.get(i);
        if (ins.kind != TacInstruction.Kind.CALL) continue;
        String calleeSig = ins.calleeSig;
        if (calleeSig == null || calleeSig.isEmpty()) continue;
        if (!includeLibraries && isLibrary(calleeSig)) continue;
        // prefer same-enclosing-class match over the
        // global bare-name map. Without this, a call like
        // {@code bad_source()} from 16 different V21 files
        // would all resolve to the FIRST candidate with that
        // bare-name (insertion-order in the global map),
        // silently collapsing 15 of 16 callers onto the same
        // callee node and breaking the cross-fn summary fold.
        String nodeSig =
            r723ResolveCalleeSigGlobal(calleeSig, callerMk, bareNameToCanonical, ins.args);

        // normalize to MergedProgram merged key so
        // {@link org.plumb.dfa.engine.DfaEngine#orderedFunctions}
        // can resolve the FunctionNode back to a TacFunction.
        // The store keys by bare signature, so look up the
        // function body and rebuild the merged key here.
        if (nodeSig != null && !nodeSig.contains("::")) {
          TacFunction calleeBody = store.functionBySignature(nodeSig).orElse(null);
          if (calleeBody != null) {
            String enc = calleeBody.enclosingClass();
            if (enc != null && !enc.isEmpty()) {
              nodeSig = enc + " :: " + nodeSig;
            }
          }
        }

        FunctionNode callee = new FunctionNode(nodeSig);
        nodes.add(callee);
        forward.computeIfAbsent(callee, k -> new ArrayList<>());
        reverse.computeIfAbsent(callee, k -> new ArrayList<>());

        CallSite cs = new CallSite(caller, callee, i, ins.line, CallSite.Kind.DIRECT);
        forward.get(caller).add(cs);
        reverse.get(callee).add(cs);
      }
    }

    CallGraph cg = CallGraphBuilder.buildFromMaps(nodes, forward, reverse);
    LOG.info(
        "GlobalCallGraph built: {} functions across {} source files, {} call sites, {} entry"
            + " points",
        store.size(),
        store.allSourceFiles().size(),
        sumSizes(forward),
        cg.entryPoints().size());
    return cg;
  }

  /**
   * Global variant of {@code CallGraphBuilder.resolveCalleeSigStatic}.
   *
   * <p>Resolves a {@code calleeSig} emitted by the IR builder to a canonical signature that matches
   * a function in the store. The logic mirrors the per-program helper:
   *
   * <ol>
   *   <li>If the raw sig already matches a real function in the store, keep it as-is.
   *   <li>Otherwise, extract the {@code bare-name/arity} and look it up in the pre-built {@code
   *       bareNameToCanonical} map. The first candidate wins (overload-disambiguation note: without
   *       param types at the call site, we can't pick a more specific overload).
   *   <li>If neither lookup hits, return the raw {@code calleeSig} unchanged : the {@link
   *       FunctionNode} will become a placeholder that the dataflow walk can still report as
   *       "callee not modelled".
   * </ol>
   */
  /**
   * variant of {@link #r45ResolveCalleeSigGlobal} that prefers a candidate whose enclosing class
   * matches the caller's enclosing class. Without this preference, a call like {@code bad_source()}
   * from a file in package X resolves to the FIRST registered function with the bare name, even if
   * that function lives in package Y. That collapses all cross-file variants onto one callee node
   * and breaks the per-file summary fold (V21 CWE789 etc.).
   */
  private String r723ResolveCalleeSigGlobal(
      String calleeSig,
      String callerMergedKey,
      java.util.Map<String, java.util.List<String>> bareNameToCanonical,
      List<TacOperand> args) {
    // Delegate to existing core for the basic resolution.
    String resolved =
        CallGraphBuilder.r48ResolveCalleeSigCore(
            sig -> store.functionBySignature(sig).isPresent(),
            calleeSig,
            bareNameToCanonical,
            args);
    if (resolved == null
        || resolved.contains("::")
        || !store.functionBySignature(resolved).isPresent()) {
      return resolved;
    }
    // The resolved sig is bare (no "::"). Prefer a function
    // whose enclosing class matches the caller's enclosing class.
    String callerEnc = enclosingClassOf(callerMergedKey);
    if (callerEnc == null || callerEnc.isEmpty()) {
      return resolved;
    }
    String bare = CallGraphBuilder.bareNameAndArity(calleeSig);
    if (bare == null) return resolved;
    int slash = bare.indexOf('/');
    if (slash > 0) {
      int dot = bare.lastIndexOf('.', slash);
      if (dot > 0) bare = bare.substring(dot + 1);
    }
    // Compare each candidate's bare-name form (signature may be
    // "name():int" while bare is "name/0"; startsWith would never match).
    //
    // ONLY THE CALLER'S OWN CLASS IS SEARCHED. The call site names a
    // method, and the method it can reach is the one the caller's class
    // declares. Walking every function in the store for every call site
    // is O(calls x functions) -- and a corpus of many files that each
    // declare the same method name makes the store large enough that the
    // scan stops finishing at all.
    for (TacFunction candidate : store.functionsOfClass(callerEnc)) {
      if (!candidate.enclosingClass().equals(callerEnc)) continue;
      String candBare = CallGraphBuilder.bareNameAndArity(candidate.signature());
      if (!bare.equals(candBare)) continue;
      return callerEnc + " :: " + candidate.signature();
    }
    return resolved;
  }

  private static String enclosingClassOf(String mergedKey) {
    if (mergedKey == null) return null;
    int sep = mergedKey.indexOf(" :: ");
    return sep < 0 ? "" : mergedKey.substring(0, sep);
  }

  private String r45ResolveCalleeSigGlobal(
      String calleeSig,
      java.util.Map<String, java.util.List<String>> bareNameToCanonical,
      List<TacOperand> args) {
    // Delegate to the shared
    // arg-type-aware overload
    // disambiguation core. The
    // per-TU and Global code paths
    // differ only in the
    // function-existence predicate
    // (program.function(sig) vs
    // store.functionBySignature(sig));
    // the resolution logic is
    // identical. Previously,
    // Global had its own copies
    // of extractParamTypes,
    // tacOperandTypeName, and the
    // arg-type walk, with the
    // Global versions being a
    // strict subset (only String
    // was normalized, not the
    // full set of primitive
    // wrappers). Sharing the
    // helper is a strict
    // improvement.
    return CallGraphBuilder.r48ResolveCalleeSigCore(
        sig -> store.functionBySignature(sig).isPresent(), calleeSig, bareNameToCanonical, args);
  }

  private static boolean isLibrary(String sig) {
    // Anything in java.*, javax.*, com.sun.*,
    // jdk.*, sun.* is treated as opaque library
    // code. We also keep the explicit prefixes
    // from the TU-local builder for backwards
    // compatibility with the original tests.
    if (sig.startsWith("java.")
        || sig.startsWith("javax.")
        || sig.startsWith("com.sun.")
        || sig.startsWith("jdk.")
        || sig.startsWith("sun.")) {
      return true;
    }
    if (sig.startsWith("java/lang/")
        || sig.startsWith("javax/")
        || sig.startsWith("Ljava/")
        || sig.startsWith("Ljavax/")) {
      return true;
    }
    return false;
  }

  private static int sumSizes(Map<FunctionNode, List<CallSite>> m) {
    int s = 0;
    for (List<CallSite> l : m.values()) s += l.size();
    return s;
  }

  /**
   * build a MergedProgram-style merged key for a {@link TacFunction} so the FunctionNode id matches
   * the key {@link org.plumb.tac.ir.MergedProgram} uses to index its {@code function} map. Without
   * this, lookups via {@code program.function(nodeSig)} in {@code DfaEngine.orderedFunctions}
   * silently return null for every cross-method / cross-file Juliet CWE789/400 variant
   * (V21/V22/V42/V45/V52/V66-V75/V81) so the cross-function summary-fold missed the taint
   * propagation.
   */
  private static String mergedKey(final TacFunction fn) {
    String enc = fn.enclosingClass();
    if (enc == null || enc.isEmpty()) {
      return fn.signature();
    }
    return enc + " :: " + fn.signature();
  }

  /**
   * Convenience: build a {@link GlobalTACStore} from a list of programs, then build the call graph.
   * Equivalent to {@code new GlobalCallGraphBuilder(GlobalTACStore.of(programs)).build()}.
   */
  public static CallGraph buildFromPrograms(List<TacProgram> programs) {
    return new GlobalCallGraphBuilder(GlobalTACStore.of(programs)).build();
  }
}

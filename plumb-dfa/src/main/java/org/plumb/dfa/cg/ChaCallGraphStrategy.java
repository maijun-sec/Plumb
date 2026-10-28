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
 * Class Hierarchy Analysis call-graph strategy.
 *
 * <p>For every {@code CALL} / virtual-invoke instruction in the program, the strategy:
 *
 * <ol>
 *   <li>Looks up the callee signature in the instruction's {@code calleeSig} field.
 *   <li>Resolves the call to the resolved callee (direct) plus every override of the callee's
 *       method in any class loaded in the same program. For the v1 (CHA), we conservatively include
 *       the direct callee and any method with the same simple name + arity, regardless of
 *       inheritance. This is the standard CHA over-approximation: every subtype of the receiver's
 *       static type is considered a possible target.
 *   <li>Filters out library calls (JRE methods) unless {@code includeLibraries} is set.
 * </ol>
 *
 * <p>This is the default strategy. It is fast (linear in the number of call sites) and conservative
 * (over-approximates virtual targets). For tighter precision, use {@link RtaCallGraphStrategy}.
 */
public final class ChaCallGraphStrategy implements CallGraphStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(ChaCallGraphStrategy.class);

  private final TacProgram program;
  private final boolean includeLibraries;

  public ChaCallGraphStrategy(TacProgram program) {
    this(program, false);
  }

  public ChaCallGraphStrategy(TacProgram program, boolean includeLibraries) {
    this.program = program;
    this.includeLibraries = includeLibraries;
  }

  @Override
  public String name() {
    return "cha";
  }

  @Override
  public CallGraph build() {
    Map<String, List<TacFunction>> bySimpleName = indexBySimpleName(program);
    // Map from bare-name+arity (e.g.
    // {@code "buildQuery/1"}) to
    // the LIST of candidate
    // canonical sigs (e.g.
    // {@code ["buildQuery(String):String",
    // "buildQuery(int):String"]})
    // so the same-class bare-name
    // calleeSig can resolve to the
    // function in the program.
    // Without this, the call graph
    // has two different
    // FunctionNodes for the same
    // target and the RPO puts the
    // caller (bad) before the
    // callee (buildQuery).
    // The LIST (not single value)
    // is needed for overload
    // disambiguation: the actual
    // call arg types pick the
    // right candidate.
    final java.util.Map<String, java.util.List<String>> bareNameToCanonical =
        buildBareNameMap(program);
    Set<FunctionNode> nodes = new LinkedHashSet<>();
    Map<FunctionNode, List<CallSite>> forward = new HashMap<>();
    Map<FunctionNode, List<CallSite>> reverse = new HashMap<>();

    for (TacFunction fn : program.functions()) {
      FunctionNode caller = new FunctionNode(fn.signature());
      nodes.add(caller);
      forward.computeIfAbsent(caller, k -> new ArrayList<>());
      final int n = fn.instructions().size();
      for (int i = 0; i < n; i++) {
        TacInstruction ins = fn.instructionAt(i);
        if (ins.kind != TacInstruction.Kind.CALL) continue;
        String calleeSig = ins.calleeSig;
        if (calleeSig == null || calleeSig.isEmpty()) continue;
        if (!includeLibraries && isLibrary(calleeSig)) continue;
        // Resolve bare-name
        // calleeSig to canonical sig
        // with arg-type-aware
        // overload disambiguation.
        String nodeSig =
            r48ResolveCalleeSigWithArgs(program, calleeSig, bareNameToCanonical, ins.args);
        // CHA: emit the direct call AND any
        // override of the same simple-name + arity
        // in any class loaded in the program.
        emitCall(caller, nodeSig, ins, nodes, forward, reverse);
        FunctionSignature fs = FunctionSignature.parse(calleeSig);
        if (fs != null) {
          for (TacFunction override :
              bySimpleName.getOrDefault(fs.method() + "/" + fs.paramTypes().size(), List.of())) {
            if (override.signature().equals(nodeSig)) continue;
            emitCall(caller, override.signature(), ins, nodes, forward, reverse);
          }
        }
      }
    }
    return finishBuild(nodes, forward, reverse);
  }

  // Reuse the shared helpers from CallGraphBuilder.
  private static java.util.Map<String, java.util.List<String>> buildBareNameMap(
      TacProgram program) {
    // Call the shared
    // {@link CallGraphBuilder#buildBareNameMap}
    // helper instead of inlining
    // a local copy. The shared
    // helper consolidates the
    // {@code bareNameAndArity}
    // hash-vs-dot form fix
    // (legacy Jimple
    // {@code G#use} → {@code G.use}
    // → same bare-name key as
    // {@code G#use()} → {@code G.use/0}).
    return CallGraphBuilder.buildBareNameMap(program);
  }

  /**
   * Local {@code bareNameAndArity} was removed in favour of the shared helper. Kept as a deprecated
   * stub for any out-of-tree caller (none expected). All in-tree callers go through {@link
   * CallGraphBuilder#bareNameAndArity} which has the hash-vs-dot consistency fix.
   */
  @Deprecated
  private static String bareNameAndArity(String sig) {
    return CallGraphBuilder.bareNameAndArity(sig);
  }

  /**
   * Stub: CallGraphBuilder's {@link CallGraphBuilder#r48ResolveCalleeSigWithArgs} is now used
   * directly. This method is kept for binary back-compat with the public CallGraphBuilder API
   * surface, but is no longer called by CHA's {@link #build()}. New callers should use {@link
   * CallGraphBuilder#r48ResolveCalleeSigWithArgs} directly.
   */
  private String r41ResolveCalleeSig(
      String calleeSig, java.util.Map<String, String> bareNameToCanonical) {
    if (program.function(calleeSig) != null) {
      return calleeSig;
    }
    String bare = bareNameAndArity(calleeSig);
    if (bare == null) {
      return calleeSig;
    }
    String canonical = bareNameToCanonical.get(bare);
    if (canonical == null) {
      return calleeSig;
    }
    return canonical;
  }

  private static String r48ResolveCalleeSigWithArgs(
      TacProgram program,
      String calleeSig,
      java.util.Map<String, java.util.List<String>> bareNameToCanonical,
      List<TacOperand> args) {
    // Delegate to the shared
    // helper in CallGraphBuilder.
    return CallGraphBuilder.r48ResolveCalleeSigWithArgs(
        program, calleeSig, bareNameToCanonical, args);
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
    forward.computeIfAbsent(callee, k -> new ArrayList<>());
    reverse.computeIfAbsent(callee, k -> new ArrayList<>());
    CallSite cs =
        new CallSite(
            caller,
            callee,
            findIndex(caller.signature(), ins, forward),
            ins.line,
            CallSite.Kind.DIRECT);
    forward.get(caller).add(cs);
    reverse.get(callee).add(cs);
  }

  private static int findIndex(
      String callerSig, TacInstruction ins, Map<FunctionNode, List<CallSite>> forward) {
    return -1; // we lose the original index; ok for CHA
  }

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

  private static boolean isLibrary(String sig) {
    // Mirror CallGraphBuilder's library filter.
    // Keeping the list short — most jdk methods
    // don't appear as call-graph nodes anyway.
    if (sig.startsWith("java.lang.Object#")) return true;
    if (sig.startsWith("java.lang.String#")) return true;
    if (sig.startsWith("java.lang.Math#")) return true;
    if (sig.startsWith("java.util.Objects#")) return true;
    if (sig.startsWith("java.util.Collections#")) return true;
    return false;
  }

  private static CallGraph finishBuild(
      Set<FunctionNode> nodes,
      Map<FunctionNode, List<CallSite>> forward,
      Map<FunctionNode, List<CallSite>> reverse) {
    CallGraph cg = CallGraphBuilder.buildFromMaps(nodes, forward, reverse);
    LOG.info("CHA call graph built: {} nodes, {} call sites", nodes.size(), sumSizes(forward));
    return cg;
  }

  private static int sumSizes(Map<FunctionNode, List<CallSite>> m) {
    int s = 0;
    for (List<CallSite> l : m.values()) s += l.size();
    return s;
  }
}

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
 * Two-level <i>call-site-sensitive</i> (2-CFA) call-graph strategy with a <b>backward caller
 * context</b> filter.
 *
 * <p>Where {@link OneCfaCallGraphStrategy} is essentially VTA with a redundant "function has seen
 * the type" filter (the receiver's points-to is already a subset of the function's union, so the
 * filter admits everything VTA admits), 2-CFA here adds a real refinement by looking at the
 * <i>callers'</i> analysis.
 *
 * <p>Concretely, for a virtual call in {@code g}, an override of the receiver's method is admitted
 * only if its declaring class appears in the union of points-to types across every <i>direct
 * caller</i> of {@code g}. The intuition is that {@code g} can only ever be reached through its
 * callers, so the set of types {@code g} can dispatch to is bounded by what its callers can pass
 * in.
 *
 * <p>Comparison with the rest of the family:
 *
 * <ul>
 *   <li><b>CHA</b> — no filtering; every override is a target.
 *   <li><b>RTA</b> — instantiated-subtype filter, no per-callsite context.
 *   <li><b>VTA</b> — per-receiver filter (most precise among forward strategies).
 *   <li><b>1-CFA</b> — VTA + "function has seen the type" (effectively a no-op refinement over VTA
 *       because the receiver's points-to is a subset of the function's union).
 *   <li><b>2-CFA</b> — VTA + "any direct caller has seen the type" (this strategy). Tightens the
 *       override set further by requiring the type to flow through at least one caller.
 * </ul>
 *
 * <p>Soundness: the preliminary call graph used to compute the caller set is itself a VTA call
 * graph (over-approximates), so the caller set is a sound over-approximation of the true caller
 * set. The resulting filter is monotone non-strictly stronger than VTA: every override admitted by
 * 2-CFA is also admitted by VTA, and 2-CFA may filter out additional overrides whose class is not
 * seen by any caller.
 */
public final class TwoCfaCallGraphStrategy implements CallGraphStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(TwoCfaCallGraphStrategy.class);

  private static final String OBJECT = "java.lang.Object";

  private final TacProgram program;
  private final boolean includeLibraries;

  public TwoCfaCallGraphStrategy(TacProgram program) {
    this(program, false);
  }

  public TwoCfaCallGraphStrategy(TacProgram program, boolean includeLibraries) {
    this.program = program;
    this.includeLibraries = includeLibraries;
  }

  @Override
  public String name() {
    return "2cfa";
  }

  @Override
  public CallGraph build() {
    // 1. Per-function points-to
    //    (reuse VTA's helper —
    //    single source of
    //    truth).
    Map<String, Map<TacOperand.TacVar, Set<String>>> pointsTo =
        VtaCallGraphStrategy.computePointsTo(program);

    // 2. Sig normalization.
    java.util.Map<String, java.util.List<String>> bareNameToCanonical =
        CallGraphBuilder.buildBareNameMap(program);

    // 3. Index overrides by
    //    method + arity.
    Map<String, List<TacFunction>> bySimpleName = indexBySimpleName(program);

    // 4. Compute the reverse
    //    caller set keyed by
    //    the function's actual
    //    signature (not the
    //    raw call-site sig).
    //    Done by re-walking
    //    the program and
    //    resolving each CALL
    //    through the r48 sig
    //    resolution helper.
    //    Also
    //    accounts for VTA
    //    override resolutions
    //    so that an override's
    //    caller set includes
    //    the dispatching
    //    function.
    Map<String, Set<String>> callerOf =
        reverseCallersResolved(pointsTo, bareNameToCanonical, bySimpleName);

    // 5. Per-function
    //    caller-context union:
    //    the union of points-to
    //    across every direct
    //    caller of the
    //    function.
    Map<String, Set<String>> callerUnion = new HashMap<>();
    for (TacFunction fn : program.functions()) {
      Set<String> union = new LinkedHashSet<>();
      Set<String> callers = callerOf.getOrDefault(fn.signature(), Set.of());
      for (String callerSig : callers) {
        for (Set<String> pts : pointsTo.getOrDefault(callerSig, Map.of()).values()) {
          union.addAll(pts);
        }
      }
      callerUnion.put(fn.signature(), union);
    }

    // 6. Pass 2: build the
    //    final 2-CFA call
    //    graph. Same shape as
    //    VTA + the
    //    caller-context
    //    refinement: the
    //    override class must
    //    appear in
    //    callerUnion(g) (in
    //    addition to passing
    //    VTA's receiver
    //    filter).
    Set<FunctionNode> nodes = new LinkedHashSet<>();
    Map<FunctionNode, List<CallSite>> forward = new HashMap<>();
    Map<FunctionNode, List<CallSite>> reverse = new HashMap<>();
    for (TacFunction fn : program.functions()) {
      FunctionNode caller = new FunctionNode(fn.signature());
      nodes.add(caller);
      forward.computeIfAbsent(caller, k -> new ArrayList<>());
      Map<TacOperand.TacVar, Set<String>> fnPts = pointsTo.getOrDefault(fn.signature(), Map.of());
      Set<String> ctx = callerUnion.getOrDefault(fn.signature(), Set.of());
      for (int i = 0; i < fn.instructions().size(); i++) {
        TacInstruction ins = fn.instructionAt(i);
        if (ins.kind != TacInstruction.Kind.CALL) continue;
        String calleeSig = ins.calleeSig;
        if (calleeSig == null || calleeSig.isEmpty()) continue;
        if (!includeLibraries && isLibrary(calleeSig)) continue;
        // Sig normalization +
        // overload disambiguation.
        String nodeSig =
            normalizeCalleeKey(
                CallGraphBuilder.r48ResolveCalleeSigWithArgs(
                    program, calleeSig, bareNameToCanonical, ins.args));
        // Direct call: always emit.
        emitCall(caller, nodeSig, ins, nodes, forward, reverse);
        // Virtual call: VTA receiver
        // filter + 2-CFA
        // caller-context filter.
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
          // VTA filter: receiver's
          // points-to intersects
          // override class.
          if (!receiverTypes.contains(overrideClass) && !receiverTypes.contains(OBJECT)) {
            continue;
          }
          // 2-CFA refinement: the
          // override class must
          // appear in the union
          // of points-to of g's
          // direct callers.
          if (!ctx.contains(overrideClass) && !ctx.contains(OBJECT)) {
            continue;
          }
          emitCall(caller, override.signature(), ins, nodes, forward, reverse);
        }
      }
    }
    CallGraph cg = CallGraphBuilder.buildFromMaps(nodes, forward, reverse);
    LOG.info("2-CFA call graph built: {} nodes, {} call sites", nodes.size(), sumSizes(forward));
    return cg;
  }

  // ---- Pass 1: reverse caller set (keyed by function signature) ----

  /**
   * Compute the reverse caller set: for every function, the set of functions that have at least one
   * {@code CALL} instruction resolving to it.
   *
   * <p><b>Why this isn't built from the preliminary call graph directly</b>: the {@code CallSite}'s
   * callee signature is the <i>raw</i> {@code calleeSig} of the CALL (e.g. {@code "G#use()"} or
   * {@code "G#use"} depending on how the IR builder emitted it), which may not match the function's
   * actual {@code TacFunction.signature()} (e.g. {@code "G#use"}). The sig normalization already
   * resolves this mismatch, but the raw CallSite key is what the call graph stores. To keep the
   * caller-set keyed by the function's <i>actual</i> signature, we re-walk the program and resolve
   * each CALL through {@code r48ResolveCalleeSigWithArgs} before adding the (caller, callee) pair.
   */
  private Map<String, Set<String>> reverseCallersResolved(
      Map<String, Map<TacOperand.TacVar, Set<String>>> pointsTo,
      java.util.Map<String, java.util.List<String>> bareNameToCanonical,
      Map<String, List<TacFunction>> bySimpleName) {
    Map<String, Set<String>> out = new HashMap<>();
    for (TacFunction fn : program.functions()) {
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind != TacInstruction.Kind.CALL) continue;
        String calleeSig = ins.calleeSig;
        if (calleeSig == null || calleeSig.isEmpty()) continue;
        String resolved =
            CallGraphBuilder.r48ResolveCalleeSigWithArgs(
                program, calleeSig, bareNameToCanonical, ins.args);
        // Normalize the
        // resolved sig to the
        // function's actual
        // signature. The
        // resolution helper may
        // return a raw
        // call-site form
        // (e.g. "G#use()")
        // even when the
        // function's
        // signature is "G#use"
        // (no parens). This is
        // a pre-existing
        // inconsistency in
        // bareNameAndArity
        // (function with no
        // parens gets bare
        // "G#use", call with
        // parens gets bare
        // "G#use/0" — they
        // don't match). The
        // normalize helper
        // papers over it by
        // searching the
        // program for a
        // function whose
        // bare-name matches.
        String calleeKey = normalizeCalleeKey(resolved);
        out.computeIfAbsent(calleeKey, k -> new LinkedHashSet<>()).add(fn.signature());
        // Also account for
        // override resolutions:
        // if a virtual call
        // resolves to a
        // different override
        // via VTA, that
        // override's callers
        // also include fn.
        // (Without this, the
        // override's
        // caller-context would
        // be empty even when
        // there's a real
        // dispatch through a
        // base-class
        // reference.)
        TacOperand receiver = ins.args.isEmpty() ? null : ins.args.get(0);
        Set<String> receiverTypes =
            receiver instanceof TacOperand.TacVar rv
                ? pointsTo.getOrDefault(fn.signature(), Map.of()).getOrDefault(rv, Set.of())
                : Set.of();
        if (receiverTypes.isEmpty()) continue;
        FunctionSignature fs = FunctionSignature.parse(calleeSig);
        if (fs == null) continue;
        String key = fs.method() + "/" + fs.paramTypes().size();
        for (TacFunction override : bySimpleName.getOrDefault(key, List.of())) {
          if (override.signature().equals(calleeSig)) continue;
          if (override.signature().equals(resolved)) continue;
          String overrideClass = overrideClassName(override.signature());
          if (overrideClass == null) continue;
          if (!receiverTypes.contains(overrideClass) && !receiverTypes.contains(OBJECT)) {
            continue;
          }
          out.computeIfAbsent(override.signature(), k -> new LinkedHashSet<>()).add(fn.signature());
        }
      }
    }
    return out;
  }

  /**
   * Map a CALL's resolved sig to the function's actual signature. Falls through to the raw sig if
   * no match is found.
   *
   * <p>Handles the pre-existing {@code bareNameAndArity} inconsistency where a function with
   * signature "G#use" (no parens) gets bare-name "G#use" but a CALL with {@code
   * calleeSig="G#use()"} gets bare-name "G#use/0". Falls back to method-name-only matching when the
   * strict bare-name match fails.
   */
  private String normalizeCalleeKey(String sig) {
    if (sig == null || sig.isEmpty()) return sig;
    // Direct hit?
    if (program.function(sig) != null) return sig;
    // Bare-name match?
    String bare = CallGraphBuilder.bareNameAndArity(sig);
    if (bare != null) {
      for (TacFunction fn : program.functions()) {
        String fnBare = CallGraphBuilder.bareNameAndArity(fn.signature());
        if (bare.equals(fnBare)) return fn.signature();
      }
    }
    // Method-name + arity
    // fallback: extract the
    // method name from both
    // sides and match
    // independently of how
    // the bare-name form
    // normalizes.
    FunctionSignature fs = FunctionSignature.parse(sig);
    if (fs != null) {
      String method = fs.method();
      int arity = fs.paramTypes().size();
      for (TacFunction fn : program.functions()) {
        FunctionSignature fnFs = FunctionSignature.parse(fn.signature());
        if (fnFs != null && method.equals(fnFs.method()) && arity == fnFs.paramTypes().size()) {
          return fn.signature();
        }
      }
    }
    return sig;
  }

  // ---- helpers shared with VTA / RTA / 1-CFA ----

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

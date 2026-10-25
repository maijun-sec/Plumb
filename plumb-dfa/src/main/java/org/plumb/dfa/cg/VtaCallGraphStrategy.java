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
import org.plumb.tac.ir.TacType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Variable Type Analysis (VTA) call-graph strategy.
 *
 * <p>Builds a per-variable "may-point-to" set, then resolves each virtual call to only the methods
 * whose declaring class is in the receiver's points-to set. VTA is strictly more precise than CHA
 * and RTA:
 *
 * <ul>
 *   <li><b>CHA</b> — receiver's declared (static) type and all its subtypes.
 *   <li><b>RTA</b> — receiver's declared type and all its instantiated subtypes (per program-wide
 *       {@code NEW} scan).
 *   <li><b>VTA</b> — for each individual variable, the types of the concrete objects it can hold. A
 *       variable declared as {@code Animal a} can only point to {@code Dog} if the program actually
 *       assigns a {@code Dog} (or a {@code Dog}-typed return value) to {@code a}.
 * </ul>
 *
 * <p>Implementation notes:
 *
 * <ol>
 *   <li><b>Allocation sites</b> — every {@code NEW} introduces a fresh abstract object; the
 *       destination variable's points-to set gains that class. {@code CtorType} carries the class
 *       name.
 *   <li><b>Copy propagation</b> — {@code x = y} makes {@code types(x) ⊇ types(y)}.
 *   <li><b>Field/method returns</b> — {@code x = m()} adds the return type of {@code m} (or {@code
 *       Object} if unknown) to {@code types(x)}.
 *   <li><b>Field loads</b> — {@code x = y.f} adds the field's declared type to {@code types(x)}.
 *   <li><b>Conditionals / branches</b> — VTA does not refine across branches (no path-sensitivity).
 *       A variable's points-to set is the join of all assignments on all paths.
 * </ol>
 *
 * <p>The output call graph is sound (over-approximates) but tighter than CHA or RTA on programs
 * that use polymorphism through base-class references with limited subclass instantiations.
 */
public final class VtaCallGraphStrategy implements CallGraphStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(VtaCallGraphStrategy.class);

  private final TacProgram program;
  private final boolean includeLibraries;

  public VtaCallGraphStrategy(TacProgram program) {
    this(program, false);
  }

  public VtaCallGraphStrategy(TacProgram program, boolean includeLibraries) {
    this.program = program;
    this.includeLibraries = includeLibraries;
  }

  @Override
  public String name() {
    return "vta";
  }

  @Override
  public CallGraph build() {
    // 1. Per-function, per-variable
    //    points-to set. The set is the
    //    join of every assignment to the
    //    variable in the function body.
    //    Cross-function propagation is
    //    intentionally NOT done here —
    //    that's k-CFA, much more
    //    expensive. VTA is a per-function
    //    analysis.
    Map<String, Map<TacOperand.TacVar, Set<String>>> pointsTo = computePointsTo(program);

    // 2. Index functions by simple
    //    name arity so we can resolve
    //    virtual calls.
    Map<String, List<TacFunction>> bySimpleName = indexBySimpleName(program);

    // 3. Build the call graph.
    // Build the bare-name →
    // canonical sig map for the
    // sig normalization. The
    // raw {@code calleeSig} from
    // the IR builder may use a
    // bare-name (e.g. "buildQuery/1")
    // for same-class calls; the map
    // lets us resolve to the
    // canonical form.
    java.util.Map<String, java.util.List<String>> bareNameToCanonical =
        CallGraphBuilder.buildBareNameMap(program);
    Set<FunctionNode> nodes = new LinkedHashSet<>();
    Map<FunctionNode, List<CallSite>> forward = new HashMap<>();
    Map<FunctionNode, List<CallSite>> reverse = new HashMap<>();
    for (TacFunction fn : program.functions()) {
      FunctionNode caller = new FunctionNode(fn.signature());
      nodes.add(caller);
      forward.computeIfAbsent(caller, k -> new ArrayList<>());
      Map<TacOperand.TacVar, Set<String>> fnPts = pointsTo.getOrDefault(fn.signature(), Map.of());
      final int n = fn.instructions().size();
      for (int i = 0; i < n; i++) {
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
        // 3a. Direct call: always emit.
        emitCall(caller, nodeSig, ins, nodes, forward, reverse);
        // 3b. Virtual dispatch: find
        //     methods of the same
        //     name/arity whose declaring
        //     class is in the
        //     receiver's points-to set.
        TacOperand receiver = ins.args.isEmpty() ? null : ins.args.get(0);
        Set<String> receiverTypes =
            receiver instanceof TacOperand.TacVar rv ? fnPts.getOrDefault(rv, Set.of()) : Set.of();
        if (receiverTypes.isEmpty()) continue;
        FunctionSignature fs = FunctionSignature.parse(calleeSig);
        if (fs == null) continue;
        String key = fs.method() + "/" + fs.paramTypes().size();
        for (TacFunction override : bySimpleName.getOrDefault(key, List.of())) {
          if (override.signature().equals(nodeSig)) continue;
          String overrideClass = overrideClassName(override.signature());
          if (overrideClass == null) continue;
          if (!receiverTypes.contains(overrideClass) && !receiverTypes.contains(OBJECT)) continue;
          // filter candidates by PARAMETER TYPE, not just name and arity.
          // Keying on `method/arity` alone let `process(int)` bind to a
          // `process(String)` override, so a primitive int call site was treated
          // as flowing into a String sink and reported (e.g. a bogus CWE-89). A
          // candidate whose declared parameter types cannot accept the call's
          // actual argument types is not a legal override for this call site.
          if (!paramsAcceptArgs(override, ins)) continue;
          emitCall(caller, override.signature(), ins, nodes, forward, reverse);
        }
      }
    }
    CallGraph cg = CallGraphBuilder.buildFromMaps(nodes, forward, reverse);
    LOG.info("VTA call graph built: {} nodes, {} call sites", nodes.size(), sumSizes(forward));
    return cg;
  }

  /**
   * For every function, build a per-variable points-to map. The map is conservative
   * (over-approximates): a variable's points-to set is the union of all types ever assigned to it
   * on any path.
   */
  static Map<String, Map<TacOperand.TacVar, Set<String>>> computePointsTo(TacProgram program) {
    Map<String, Map<TacOperand.TacVar, Set<String>>> out = new HashMap<>();
    for (TacFunction fn : program.functions()) {
      Map<TacOperand.TacVar, Set<String>> fnMap = new LinkedHashMap<>();
      for (TacOperand.TacVar p : fn.params()) {
        fnMap.put(p, new LinkedHashSet<>(declaredTypes(p)));
      }
      for (TacOperand.TacVar l : fn.locals()) {
        fnMap.put(l, new LinkedHashSet<>(declaredTypes(l)));
      }
      final int n = fn.instructions().size();
      for (int i = 0; i < n; i++) {
        TacInstruction ins = fn.instructionAt(i);
        if (ins.dest == null || !(ins.dest instanceof TacOperand.TacVar d)) continue;
        Set<String> add =
            switch (ins.kind) {
              case NEW -> ins.ctorType == null ? Set.of(OBJECT) : Set.of(ins.ctorType.toString());
              case MOVE ->
                  ins.lhs1 instanceof TacOperand.TacVar src
                      ? new LinkedHashSet<>(fnMap.getOrDefault(src, Set.of()))
                      : declaredTypes(d);
              case CALL -> {
                // Conservative: assign
                // Object / the return
                // type (we don't know
                // the callee's return
                // type without a
                // summary).
                yield Set.of(OBJECT);
              }
              case FIELDLOAD -> Set.of(OBJECT);
              case ARRAYLOAD -> Set.of(OBJECT);
              case BINOP -> primitiveTypes(d);
              case RETURN -> Set.of();
              default -> Set.of();
            };
        if (add.isEmpty()) continue;
        // Always store a mutable
        // set so subsequent
        // addAll() calls work.
        // The params/locals
        // initialisation above
        // already wraps in
        // LinkedHashSet, so
        // .addAll on the existing
        // value is safe.
        fnMap.computeIfAbsent(d, k -> new LinkedHashSet<>()).addAll(add);
      }
      out.put(fn.signature(), fnMap);
    }
    return out;
  }

  private static final String OBJECT = "java.lang.Object";

  private static Set<String> declaredTypes(TacOperand.TacVar v) {
    TacType t = v.type();
    if (t == null) return Set.of(OBJECT);
    return switch (t.kind()) {
      case CLASS, ARRAY -> Set.of(t.toString());
      case NULL -> Set.of(OBJECT);
      case VOID -> Set.of();
      case STRING -> Set.of("java.lang.String");
      default -> Set.of();
    };
  }

  /**
   * can {@code candidate}'s declared parameter types accept the actual arguments at this call site?
   *
   * <p>This is a necessary (not sufficient) condition for an override to be a legal target. It
   * exists because resolving virtual calls on {@code name/arity} alone produced provable false
   * positives: a call {@code handler.process(42)} was bound to {@code process(String)}, so the int
   * argument was treated as flowing into a string sink and reported as SQL injection.
   *
   * <p>The check is deliberately permissive where the IR is imprecise — an unknown or boxed
   * argument type is accepted rather than rejected, because a false negative here would silently
   * drop a real edge. We only reject when BOTH sides are known and are primitives of different
   * kinds, or when both are known reference types that cannot be related (we have no subtype
   * lattice here, so we accept any reference-to-reference pairing).
   */
  private static boolean paramsAcceptArgs(TacFunction candidate, TacInstruction call) {
    if (candidate == null || call == null) return true;
    java.util.List<TacOperand.TacVar> params = candidate.params();
    java.util.List<TacOperand> args = call.args;
    if (params == null || args == null) return true;
    // args[0] is the receiver for instance calls.
    int n = Math.min(params.size(), Math.max(0, args.size() - 1));
    for (int i = 0; i < n; i++) {
      TacOperand arg = args.get(i + 1);
      if (!(arg instanceof TacOperand.TacVar av)) continue;
      if (!isAssignable(av.type(), params.get(i).type())) return false;
    }
    return true;
  }

  /**
   * a conservative assignability test over {@link TacType}. Returns {@code true} whenever either
   * side is unknown, so imprecision only ever widens the call graph.
   *
   * <p>The cases we DO reject are exactly the ones that produced provable false positives: a
   * primitive argument offered to a {@code String} parameter (or vice versa), and two different
   * primitives. A primitive cannot be a String, and the engine has no boxing model, so binding them
   * together is never correct — it is what made {@code numbers.process(7)} reach a {@code
   * process(String)} SQL sink.
   *
   * <p>Reference-to-reference pairs are accepted: this IR exposes no subtype lattice, so {@code
   * String -> CharSequence} and {@code ArrayList -> List} are both legal and we have no cheap way
   * to tell them apart from an illegal widening.
   */
  private static boolean isAssignable(TacType from, TacType to) {
    if (from == null || to == null) return true;
    TacType.Kind fk = from.kind();
    TacType.Kind tk = to.kind();
    if (fk == TacType.Kind.UNKNOWN
        || tk == TacType.Kind.UNKNOWN
        || fk == TacType.Kind.NULL
        || tk == TacType.Kind.NULL
        || fk == TacType.Kind.VOID
        || tk == TacType.Kind.VOID) {
      return true;
    }
    boolean fromPrim = isPrimitiveKind(fk);
    boolean toPrim = isPrimitiveKind(tk);
    if (fromPrim && toPrim) return fk == tk;
    // A primitive on exactly one side is only legal when the other side is a boxed/unknown
    // reference we cannot reason about. STRING and CLASS are concrete reference kinds, and a
    // primitive is never assignable to them without a boxing conversion the IR does not model.
    if (fromPrim != toPrim) {
      TacType.Kind other = fromPrim ? tk : fk;
      return other == TacType.Kind.ARRAY; // arrays accept any element expression
    }
    return true; // reference -> reference
  }

  private static boolean isPrimitiveKind(TacType.Kind k) {
    return switch (k) {
      case INT, LONG, DOUBLE, FLOAT, SHORT, BYTE, CHAR, BOOLEAN -> true;
      default -> false;
    };
  }

  /** test seam for {@link #isAssignable}, which is otherwise private. */
  static boolean isAssignableForTest(TacType from, TacType to) {
    return isAssignable(from, to);
  }

  private static Set<String> primitiveTypes(TacOperand.TacVar v) {
    // A BINOP result is one of the
    // operand's types, or the
    // destination's declared type.
    return declaredTypes(v);
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

  private static String overrideClassName(String sig) {
    FunctionSignature fs = FunctionSignature.parse(sig);
    if (fs == null) return null;
    return fs.className();
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
    CallSite cs = new CallSite(caller, callee, -1, ins.line, CallSite.Kind.DIRECT);
    forward.get(caller).add(cs);
    reverse.get(callee).add(cs);
  }

  private static boolean isLibrary(String sig) {
    if (sig.startsWith("java.lang.Object#")) return true;
    if (sig.startsWith("java.lang.String#")) return true;
    if (sig.startsWith("java.lang.Math#")) return true;
    if (sig.startsWith("java.util.Objects#")) return true;
    if (sig.startsWith("java.util.Collections#")) return true;
    return false;
  }

  private static int sumSizes(Map<FunctionNode, List<CallSite>> m) {
    int s = 0;
    for (List<CallSite> l : m.values()) s += l.size();
    return s;
  }
}

package org.plumb.dfa.summary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.plumb.tac.ir.TacFunction;

/**
 * The summary of a single {@link TacFunction}.
 *
 * <p>A summary is the answer to the question "what effects does this function have on its
 * parameters and on its return value, that the engine has been able to observe?" The {@link
 * org.plumb.dfa.engine.DfaEngine} computes a summary for every function it analyses (in reverse
 * post-order; callees first), and stores it in a {@link FunctionSummaryCache}. When a caller-side
 * checker hits a call site, it queries the cache and projects only the operations it cares about (a
 * TaintChecker looks for {@link Operation.TaintPropagate}, a SqlInjection checker looks for {@link
 * Operation.Sink} with {@code familyKey == "sql.sinks"}, and so on).
 *
 * <h2>What's IN the summary</h2>
 *
 * <ul>
 *   <li>{@code paramOps} -- for each parameter index (0-based, after the receiver if any), the set
 *       of {@link Operation}s the function performs on that parameter's value. A parameter with no
 *       entries was either not used or its effects matched no known pattern (the engine is silent
 *       in that case; absence of an entry is NOT a guarantee of "no effect").
 *   <li>{@code returnOps} -- operations on the function's return value. These tell the caller what
 *       to expect when the return is used in a sink or stored in a field.
 *   <li>{@code calleesReached} -- signatures of all called functions (so the engine can reconstruct
 *       the call graph at summary time, without re-traversing the IR).
 *   <li>{@code hasUnmodelledBehavior} -- true if the function has at least one {@link
 *       Operation.HasUnmodelledBehavior}. Stored as a flag for O(1) consumer queries, rather than
 *       forcing the consumer to walk the per-param sets.
 *   <li>{@code stringOps} -- ordered list of {@link StringOp} passthroughs observed inside this
 *       function (e.g. {@code arg.toLowerCase()}, {@code arg.trim()}). The two-pass analyzer uses
 *       this list to re-apply the passthroughs at the call site so a {@code helper(tainted) →
 *       t.toLowerCase() → return t} chain propagates taint to the caller's dest through the
 *       intermediate local.
 *   <li>{@code fieldOps} -- ordered list of {@link FieldOp} writes observed inside this function
 *       (e.g. {@code this.data = d}). Each {@code FieldOp} carries the parameter index of the
 *       source so the {@code FieldTaintTracker} can propagate taint from the matching caller arg to
 *       the field write.
 * </ul>
 *
 * <h2>What's NOT in the summary</h2>
 *
 * <p>Findings that don't depend on the parameters. A direct finding (a {@code "dead code"} in a
 * function body, an uncaught {@code RuntimeException}, a {@code System.exit(0)} at the top of
 * {@code main}) is reported in the analysis phase and never reaches the summary. The summary is
 * only for param-bound effects that matter at the call site.
 *
 * <h2>Immutability</h2>
 *
 * <p>All instances are immutable. Build them via {@link Builder}; merge two summaries via {@link
 * #merge(FunctionSummary)}. Thread-safe to share across threads once constructed.
 */
public final class FunctionSummary {

  private final String signature;
  private final Map<Integer, Set<Operation>> paramOps;
  private final Set<Operation> returnOps;
  private final Set<String> calleesReached;
  private final boolean hasUnmodelledBehavior;
  // True if the function has at least one non-RETURN instruction
  // in its body. Used by the cross-function summary-fold path to
  // detect "callee does something non-trivial" so we can
  // conservatively propagate caller-arg taint through the function
  // even when no taint propagated during the callee's standalone
  // analysis (because the param was not tainted standalone). For
  // example, for
  //   String buildQuery(String u) {
  //     return "SELECT ... " + u + " ...";
  //   }
  // analyzed standalone, the param `u` is not tainted, so no
  // TaintPropagate is recorded. The fold's existing dest-tainting
  // rule (returnOps contains TaintPropagate) misses this case.
  // The "hasBody" signal lets the conservative rule fire: caller
  // passes a tainted arg to a non-trivial callee → dest is tainted.
  private final boolean hasBody;
  // String-passthrough ops and
  // field-write ops observed inside this
  // function. Both are Lists (not Sets)
  // because order matters: the cross-function
  // analyzer re-applies the passthroughs in
  // the order they were observed.
  private final List<StringOp> stringOps;
  private final List<FieldOp> fieldOps;

  private FunctionSummary(
      String signature,
      Map<Integer, Set<Operation>> paramOps,
      Set<Operation> returnOps,
      Set<String> calleesReached,
      boolean hasUnmodelledBehavior,
      boolean hasBody,
      List<StringOp> stringOps,
      List<FieldOp> fieldOps) {
    this.signature = signature;
    // Defensive: wrap in unmodifiable views so
    // the builder can hand out the underlying
    // sets during construction without
    // worrying about post-construction
    // mutation.
    Map<Integer, Set<Operation>> unmodParams = new HashMap<>();
    for (Map.Entry<Integer, Set<Operation>> e : paramOps.entrySet()) {
      unmodParams.put(e.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue())));
    }
    this.paramOps = Collections.unmodifiableMap(unmodParams);
    this.returnOps = Collections.unmodifiableSet(new LinkedHashSet<>(returnOps));
    this.calleesReached = Collections.unmodifiableSet(new LinkedHashSet<>(calleesReached));
    this.hasUnmodelledBehavior = hasUnmodelledBehavior;
    this.hasBody = hasBody;
    // Defensive copies of the new
    // lists. Both are ordered (consumers
    // walk them in order) so ArrayList is
    // the right underlying type. The
    // builder already produces ArrayList
    // instances; we copy to break aliasing.
    this.stringOps = Collections.unmodifiableList(new ArrayList<>(stringOps));
    this.fieldOps = Collections.unmodifiableList(new ArrayList<>(fieldOps));
  }

  /**
   * The empty / trivial summary. Returned when a function has no observable effects, either because
   * the function is a no-op (empty body) or because the engine chose not to record any. The cache
   * always returns this same instance for "no summary available"; a consumer never has to deal with
   * a null summary.
   */
  public static FunctionSummary empty() {
    return EMPTY;
  }

  private static final FunctionSummary EMPTY =
      new FunctionSummary("", Map.of(), Set.of(), Set.of(), false, false, List.of(), List.of());

  /**
   * Builder for {@code FunctionSummary}. Used by checkers during their {@code summarizeFunction}
   * callback. All builder methods are null-safe: passing a null arg index silently no-ops, since
   * "no observation" is a valid contribution to the summary.
   */
  public static final class Builder {
    private final String signature;
    private final Map<Integer, Set<Operation>> paramOps = new HashMap<>();
    private final Set<Operation> returnOps = new LinkedHashSet<>();
    private final Set<String> calleesReached = new LinkedHashSet<>();
    private boolean hasUnmodelledBehavior;
    private boolean hasBody;
    // Per-summary accumulators for string-passthrough ops and
    // field-write ops. Lists (not Sets) because order matters
    // for the two-pass re-application.
    private final List<StringOp> stringOps = new ArrayList<>();
    private final List<FieldOp> fieldOps = new ArrayList<>();

    public Builder(String signature) {
      this.signature = Objects.requireNonNull(signature, "signature");
    }

    /**
     * Record an operation on the given parameter. The {@code paramIndex} is 0-based and refers to
     * the parameter list in {@code TacFunction.params()} (not including {@code this} / the
     * receiver; a static method is straightforward, an instance method has its receiver at position
     * -1 in the engine's view but the user-visible params start at 0).
     */
    public Builder recordParamOp(int paramIndex, Operation op) {
      if (op == null) return this;
      paramOps.computeIfAbsent(paramIndex, k -> new LinkedHashSet<>()).add(op);
      if (op instanceof Operation.HasUnmodelledBehavior) {
        hasUnmodelledBehavior = true;
      }
      return this;
    }

    /** Record an operation on the return value. */
    public Builder recordReturnOp(Operation op) {
      if (op == null) return this;
      returnOps.add(op);
      if (op instanceof Operation.HasUnmodelledBehavior) {
        hasUnmodelledBehavior = true;
      }
      return this;
    }

    /**
     * Record that this function called into {@code calleeSig}. Used for call-graph cross-reference
     * and for consumer checkers that need to know which callees were reached.
     */
    public Builder recordCallee(String calleeSig) {
      if (calleeSig != null && !calleeSig.isEmpty()) {
        calleesReached.add(calleeSig);
      }
      return this;
    }

    /**
     * Explicit flag for unmodelled behaviour (e.g. when the engine decides to short- circuit before
     * populating the per-param sets).
     */
    public Builder markUnmodelled() {
      this.hasUnmodelledBehavior = true;
      return this;
    }

    /**
     * Mark the function as having a non-trivial body. The cross-function summary-fold uses this
     * signal to apply a conservative caller-arg → dest taint passthrough: if the caller passes a
     * tainted arg to a non-trivial callee, the call's dest inherits the caller's arg taint. This
     * covers the {@code return "literal" + x + "literal"} pattern where the standalone analysis
     * doesn't see {@code x} as tainted (so no {@code TaintPropagate} on the returnOps), but the
     * caller's taint should still flow through.
     */
    public Builder markHasBody() {
      this.hasBody = true;
      return this;
    }

    /**
     * Record a string-passthrough operation observed inside this function. The {@code op} is
     * appended to the ordered list; duplicate method names are NOT deduplicated (the two-pass
     * analyzer walks the list in order and may need to distinguish {@code arg.toLowerCase().trim()}
     * from {@code arg.trim().toLowerCase()}). A {@code null} op is silently ignored.
     */
    public Builder recordStringOp(StringOp op) {
      if (op != null) {
        stringOps.add(op);
      }
      return this;
    }

    /**
     * Record a field-write operation observed inside this function. The {@code op} is appended to
     * the ordered list. Multiple writes to the same field are NOT deduplicated (different writes
     * may have different sources, and the FieldTaintTracker needs the full list to reconstruct the
     * final state). A {@code null} op is silently ignored.
     */
    public Builder recordFieldOp(FieldOp op) {
      if (op != null) {
        fieldOps.add(op);
      }
      return this;
    }

    public FunctionSummary build() {
      return new FunctionSummary(
          signature,
          paramOps,
          returnOps,
          calleesReached,
          hasUnmodelledBehavior,
          hasBody,
          stringOps,
          fieldOps);
    }
  }

  public String signature() {
    return signature;
  }

  public Map<Integer, Set<Operation>> paramOps() {
    return paramOps;
  }

  public Set<Operation> returnOps() {
    return returnOps;
  }

  public Set<String> calleesReached() {
    return calleesReached;
  }

  public boolean hasUnmodelledBehavior() {
    return hasUnmodelledBehavior;
  }

  /**
   * The ordered list of string-passthrough operations observed inside this function. The list
   * preserves the order in which the operations were recorded (the two-pass analyzer walks it in
   * order to re-apply the passthroughs at the caller). Empty when no passthroughs were observed.
   * Returns an unmodifiable view.
   */
  public List<StringOp> stringOps() {
    return stringOps;
  }

  /**
   * The ordered list of field-write operations observed inside this function. The list preserves
   * the order in which the operations were recorded. Empty when no field writes were observed.
   * Returns an unmodifiable view.
   */
  public List<FieldOp> fieldOps() {
    return fieldOps;
  }

  /**
   * True if this function has at least one non-RETURN instruction in its body. Used by the
   * cross-function summary-fold path to detect "callee does something non-trivial" so a
   * conservative caller-arg → dest taint passthrough rule can fire. See {@code
   * TaintedCheckerBase.propagateTaintFromCalleeSummary} for the rule logic.
   */
  public boolean hasBody() {
    return hasBody;
  }

  /**
   * True if this summary has no recorded operations, no reached callees, and no unmodelled flag.
   * Such a function does literally nothing the engine can see (typically an empty body, or a
   * function whose every operation was a primitive arithmetic that the engine has no catalogue
   * for).
   */
  public boolean isTrivial() {
    return paramOps.isEmpty()
        && returnOps.isEmpty()
        && stringOps.isEmpty()
        && fieldOps.isEmpty()
        && !hasUnmodelledBehavior;
  }

  /**
   * Get the operations recorded against a specific parameter index. Returns an empty set (not null)
   * for out-of-range indices and for parameters with no observations; this lets consumers write
   * {@code summary.paramOpsAt(i).contains(...)} without a null check.
   */
  public Set<Operation> paramOpsAt(int paramIndex) {
    Set<Operation> ops = paramOps.get(paramIndex);
    return ops == null ? Set.of() : ops;
  }

  /**
   * Project the summary to the operations a specific consumer cares about. The filter receives each
   * {@link Operation} and returns true if it should be kept. The result is a new immutable summary
   * with the same signature but the filtered operation sets. Use this to scope a SQL checker to
   * {@code Sink} with {@code familyKey == "sql.sinks"}.
   */
  public FunctionSummary project(java.util.function.Predicate<Operation> filter) {
    Builder b = new Builder(signature);
    for (Map.Entry<Integer, Set<Operation>> e : paramOps.entrySet()) {
      for (Operation op : e.getValue()) {
        if (filter.test(op)) b.recordParamOp(e.getKey(), op);
      }
    }
    for (Operation op : returnOps) {
      if (filter.test(op)) b.recordReturnOp(op);
    }
    if (hasUnmodelledBehavior && filter.test(new Operation.HasUnmodelledBehavior("any"))) {
      b.markUnmodelled();
    }
    // hasBody is preserved through
    // project() as-is. It's a body-level
    // signal, not an Operation-level
    // signal, so the Operation filter
    // doesn't apply. Consumers that want
    // to scope the conservative
    // arg → dest passthrough rule to a
    // subset of callers can filter on
    // hasBody themselves.
    if (hasBody) b.markHasBody();
    // stringOps and fieldOps are
    // preserved through project() as-is.
    // They're not Operation subtypes, so
    // the Operation-based filter doesn't
    // apply. Consumers that want to filter
    // these can walk the lists and drop
    // unwanted entries themselves; a future
    // Predicate<Object> overload may be
    // added if demand materialises.
    for (StringOp sop : stringOps) b.recordStringOp(sop);
    for (FieldOp fop : fieldOps) b.recordFieldOp(fop);
    return b.build();
  }

  /**
   * Combine two summaries of the same function (typically the result of two different paths through
   * the body). Operations are unioned; the {@code hasUnmodelledBehavior} flag is set if either side
   * has it. The {@code signature} of the receiver is kept; the {@code other}'s signature is
   * ignored.
   */
  public FunctionSummary merge(FunctionSummary other) {
    // Trivial-with-body is a meaningful
    // signal (the "callee has a body" gate
    // for the cross-fn fold). If both sides
    // are isTrivial() but the other side has
    // a body (or this side has a body), we
    // still need to propagate the body flag.
    // The earlier short-circuit dropped a
    // trivial-with-body {@code other} against
    // a trivial empty {@code this} (returning
    // {@code this}) which silently erased
    // hasBody from the merged summary —
    // exactly the getter pattern that needs
    // to fire.
    if (other == null) return this;
    if (other.isTrivial() && !other.hasBody() && this.isTrivial() && !this.hasBody()) {
      return this;
    }
    if (this.isTrivial() && !this.hasBody()) return other;
    if (other.isTrivial() && !other.hasBody()) return this;
    Builder b = new Builder(signature);
    for (Map.Entry<Integer, Set<Operation>> e : paramOps.entrySet()) {
      for (Operation op : e.getValue()) {
        b.recordParamOp(e.getKey(), op);
      }
    }
    for (Map.Entry<Integer, Set<Operation>> e : other.paramOps.entrySet()) {
      for (Operation op : e.getValue()) {
        b.recordParamOp(e.getKey(), op);
      }
    }
    for (Operation op : returnOps) b.recordReturnOp(op);
    for (Operation op : other.returnOps) b.recordReturnOp(op);
    for (String c : calleesReached) b.recordCallee(c);
    for (String c : other.calleesReached) b.recordCallee(c);
    // stringOps and fieldOps are unioned
    // in order. The receiver's entries
    // come first (preserving the observed
    // order in the receiver); the other's
    // entries follow. Consumers walk the
    // merged list in order, so this gives
    // a sensible "either-side-observed"
    // sequence.
    for (StringOp sop : stringOps) b.recordStringOp(sop);
    for (StringOp sop : other.stringOps) b.recordStringOp(sop);
    for (FieldOp fop : fieldOps) b.recordFieldOp(fop);
    for (FieldOp fop : other.fieldOps) b.recordFieldOp(fop);
    if (hasUnmodelledBehavior || other.hasUnmodelledBehavior) {
      b.markUnmodelled();
    }
    // hasBody is set if either side
    // observed a non-trivial body. The
    // body-signal is union: if either path
    // saw a body, the merged summary
    // reports a body.
    if (hasBody || other.hasBody) {
      b.markHasBody();
    }
    return b.build();
  }

  @Override
  public String toString() {
    return "FunctionSummary{"
        + signature
        + ", paramOps="
        + paramOps.size()
        + ", returnOps="
        + returnOps.size()
        + ", callees="
        + calleesReached.size()
        + ", stringOps="
        + stringOps.size()
        + ", fieldOps="
        + fieldOps.size()
        + ", unmodelled="
        + hasUnmodelledBehavior
        + '}';
  }
}

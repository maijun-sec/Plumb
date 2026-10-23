package org.plumb.dfa.summary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.plumb.dfa.config.FactKind;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.dfa.fact.Fact;
import org.plumb.dfa.fact.FactBag;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * Consumer for the {@code SIDE_EFFECT_RECORDED} fact written by the {@link SummaryApplier} at every
 * {@code CALL} instruction site.
 *
 * <p>The consumer sits between the {@link SummaryApplier} and the checkers that hold per-operand
 * caches of derived facts (e.g. "operand {@code x} is non-null", "operand {@code y} is non-zero",
 * "operand {@code z} is constant 5"). When a callee is known to write to a particular field or
 * parameter, any cached fact about that field / parameter is no longer valid ? the consumer records
 * the invalidation and exposes a lookup so checkers can decide what to do.
 *
 * <h2>Invalidation keys</h2>
 *
 * <p>Each entry in a {@link SideEffect} string ({@code writes-this:foo}, {@code writes-param:0},
 * {@code writes-static:Foo.bar}, …) is normalised into a stable key the checkers can match on:
 *
 * <pre>
 *   writes-this:&lt;name&gt;     → "this:&lt;name&gt;"
 *   writes-param:&lt;i&gt;       → "param:&lt;i&gt;"
 *   writes-static:&lt;FQCN.f&gt;  → "static:&lt;FQCN.f&gt;"
 *   may-throw, calls:*         → no per-operand invalidation
 * </pre>
 *
 * <p>Checkers compare their operand's name to the consumer's {@link #invalidationKeys()} to decide
 * whether a cached fact about that operand has been invalidated. The matching is intentionally
 * string-keyed (not TacVar-keyed) so the consumer is trivial to mock / test and so checkers don't
 * have to know which receiver / parameter a CALL site actually used to make a robust call.
 *
 * <h2>Per-function lifecycle</h2>
 *
 * <p>The consumer is per-function like the {@link FactBag} itself: the engine creates one fresh
 * consumer at the start of every function, lets the checkers / engine feed it CALL observations
 * during analysis, and discards it at the end. Cross-function invalidation is the callee summary's
 * job, not this consumer's.
 *
 * <h2>Why not just walk the bag</h2>
 *
 * <p>The bag has no notion of "this fact was invalidated by a call that happened later". The bag is
 * a flat per-function store; the consumer adds a small temporal layer on top (the invalidation log)
 * so checkers that need the temporal view (e.g. {@code UnusedFieldChecker}, {@code
 * NullDerefChecker}, {@code DivideByZeroChecker}) can consult it without each one re-implementing
 * the side-effect parser.
 */
public final class SideEffectConsumer {

  /**
   * Map from invalidation key to the list of (encoded) call sites that caused the invalidation. The
   * list is in observation order (the first call site is the first to invalidate; later ones are
   * no-ops because the key is already invalidated).
   */
  private final Map<String, List<String>> invalidations = new LinkedHashMap<>();

  private FactKind sideEffectKind;
  private boolean prepared;

  /**
   * Initialise the consumer against an engine's {@link AnalysisContext}. Resolves the {@link
   * BuiltinFactKinds#SIDE_EFFECT_RECORDED} kind from the registry; a no-op if the registry doesn't
   * have it.
   */
  public void prepare(AnalysisContext ctx) {
    Objects.requireNonNull(ctx, "ctx");
    this.sideEffectKind =
        ctx.config().factKinds().byName(BuiltinFactKinds.SIDE_EFFECT_RECORDED).orElse(null);
    this.prepared = true;
  }

  /**
   * Read the {@code SIDE_EFFECT_RECORDED} fact that {@link SummaryApplier#applySideEffect} wrote
   * for {@code call} and record every per-operand invalidation. A no-op if the kind isn't
   * registered, the call has no recorded fact, or the fact is the literal {@code "pure"} / empty.
   *
   * <p>Called by the engine after {@link SummaryApplier#apply} at every {@code CALL} instruction.
   */
  public void recordCall(TacInstruction call, AnalysisContext ctx) {
    if (!prepared) prepare(ctx);
    if (sideEffectKind == null) return;
    if (call == null || call.kind != TacInstruction.Kind.CALL) return;
    TacOperand site = call.dest != null ? call.dest : syntheticSite(call);
    FactBag bag = ctx.factBag();
    for (Fact f : bag.factsOf(site)) {
      if (!f.hasKind(sideEffectKind)) continue;
      String v = f.value();
      if (v == null || v.isEmpty() || "pure".equals(v)) continue;
      parseAndInvalidate(v, call);
    }
  }

  /**
   * Whether any side-effect call observed by this consumer has invalidated facts about the operand
   * identified by {@code key}. The key conventions are described in the class javadoc.
   */
  public boolean wasInvalidated(String key) {
    if (key == null) return false;
    return invalidations.containsKey(key);
  }

  /**
   * Snapshot of the set of currently invalidation keys. The set is unmodifiable; callers can
   * iterate it to ask "what state has this function already invalidated?".
   */
  public Set<String> invalidationKeys() {
    return Collections.unmodifiableSet(invalidations.keySet());
  }

  /**
   * The call sites (in observation order) that invalidated a given key. Empty list if the key
   * wasn't invalidated. Each entry is the textual encoding {@code "call@<line>"} of the offending
   * {@code CALL} instruction, for debug / finding messages.
   */
  public List<String> invalidationSitesFor(String key) {
    if (key == null) return List.of();
    List<String> l = invalidations.get(key);
    return l == null ? List.of() : Collections.unmodifiableList(l);
  }

  /**
   * Total number of (key, call-site) pairs recorded. Exposed for tests and for metrics on the
   * consumer's behaviour during a Juliet sweep.
   */
  public int invalidationCount() {
    int n = 0;
    for (List<String> l : invalidations.values()) n += l.size();
    return n;
  }

  /**
   * Convenience helper for checkers: return the bag's facts for {@code operand} filtered to drop
   * the ones whose operand key has been invalidated by a side-effect call. The key is derived from
   * the operand's kind (a {@link TacOperand.TacVar} whose name is {@code "this"} maps to {@code
   * "this"} for the param-arg case; static field keys are passed through verbatim).
   */
  public List<Fact> liveFactsOf(TacOperand operand, FactBag bag) {
    if (operand == null || bag == null) return List.of();
    // Direct lookup: if the operand name
    // itself is a registered key, the
    // whole fact list is invalidated.
    if (operand instanceof TacOperand.TacVar v && wasInvalidated(v.name())) {
      return List.of();
    }
    return bag.factsOf(operand);
  }

  /**
   * Clear the consumer. Called by the engine at the start of every function to reset the
   * per-function invalidation log.
   */
  public void clear() {
    invalidations.clear();
    prepared = false;
    sideEffectKind = null;
  }

  // ---------------- internals ----------------

  private void parseAndInvalidate(String value, TacInstruction call) {
    if (value == null || value.isEmpty()) return;
    String site = "call@" + call.line;
    for (String flag : value.split(",")) {
      String key = normalise(flag);
      if (key == null) continue;
      invalidations.computeIfAbsent(key, k -> new ArrayList<>()).add(site);
    }
  }

  /**
   * Map a single {@code writes-this:foo} / {@code writes-param:0} / … flag to an invalidation key.
   * Returns {@code null} for flags that don't invalidate a specific operand ({@code may-throw},
   * {@code calls:...}).
   */
  static String normalise(String flag) {
    if (flag == null) return null;
    if (flag.startsWith("writes-this:")) {
      return "this:" + flag.substring("writes-this:".length());
    }
    if (flag.startsWith("writes-param:")) {
      return "param:" + flag.substring("writes-param:".length());
    }
    if (flag.startsWith("writes-static:")) {
      return "static:" + flag.substring("writes-static:".length());
    }
    // may-throw, calls:... don't
    // invalidate a specific operand.
    return null;
  }

  private static TacOperand syntheticSite(TacInstruction call) {
    return new TacOperand.TacVar(
        "@call@" + Integer.toHexString(System.identityHashCode(call)), null, true);
  }
}

package org.plumb.dfa.fact;

import java.util.EnumSet;
import java.util.Set;
import org.plumb.dfa.config.FactKind;
import org.plumb.dfa.config.FactKind.Trait;
import org.plumb.dfa.config.FactKindRegistry;

/**
 * Built-in {@link FactKind}s that ship with the engine, mirroring the categories the checker series
 * already cover. The names follow the {@code <domain>.<subcategory>} convention so multiple
 * checkers in the same domain don't collide.
 *
 * <p>Each built-in has a one-line description of the dataflow role it plays and the {@link Trait}
 * set the engine consults to decide what to do automatically. Custom checkers can register their
 * own {@link FactKind}s on top of these; the registry is keyed by name, so no collision is possible
 * as long as the names are unique.
 *
 * <h2>How to pick the right trait set</h2>
 *
 * <ul>
 *   <li>{@code PROPAGATED_BY_DECL}: the fact should follow the variable through the IR (e.g. taint
 *       on a local). Omit if the fact is bound to a specific instruction site (e.g. "this CALL is a
 *       sink").
 *   <li>{@code PROPAGATED_THROUGH_CALL}: the fact should cross function boundaries. Most
 *       source-side facts have this; sink-side and "use site" facts don't.
 *   <li>{@code UNTRUSTED_SOURCE}: this fact marks the value as coming from outside the trust
 *       boundary. The engine uses this to drive "taint → sink" cross-checker cooperations.
 *   <li>{@code UNTRUSTED_SINK}: this fact marks an instruction site that consumes sensitive values.
 *       Symmetric counterpart of {@code UNTRUSTED_SOURCE}.
 *   <li>{@code NOT_PROPAGATED_THROUGH_LOOP}: the fact is conservative on loop back-edges (e.g. an
 *       induction variable's "current value" fact is meaningless after one loop iteration).
 *   <li>{@code JOINED_BY_AND}: facts of this kind at a CFG join are intersected, not unioned. Used
 *       for "this value MUST satisfy P" facts (range, non-null, etc.).
 *   <li>{@code IS_PATH_SINK}: marks the instruction as a candidate path sink for the path-sensitive
 *       analyzer (path layer + Z3 bridge).
 * </ul>
 */
public final class BuiltinFactKinds {

  private BuiltinFactKinds() {}

  // ─────── 1. Taint sources / sinks ───────

  /**
   * "This value comes from outside the trust boundary" ? request param, header, env var, file
   * content. Propagates through SSA def-use AND across call boundaries. The value carries the
   * concrete source id (e.g. {@code "bu-0001-taint-request-param"}).
   */
  public static final String UNTRUSTED_SOURCE = "dfa-taint-untrusted-source";

  public static final Set<Trait> UNTRUSTED_SOURCE_TRAITS =
      EnumSet.of(Trait.UNTRUSTED_SOURCE, Trait.PROPAGATED_BY_DECL, Trait.PROPAGATED_THROUGH_CALL);

  /**
   * "This use site consumes sensitive values" ? a SQL {@code execute} call, a {@code system} call,
   * a {@code Runtime.exec} call. The value carries the sink family key (e.g. {@code "sql.sinks"}).
   * Symmetric to {@link #UNTRUSTED_SOURCE}.
   */
  public static final String UNTRUSTED_SINK = "dfa-taint-untrusted-sink";

  public static final Set<Trait> UNTRUSTED_SINK_TRAITS =
      EnumSet.of(Trait.UNTRUSTED_SINK, Trait.IS_PATH_SINK);

  // ─────── 2. Null / zero / undefined ───────

  /**
   * "This variable may be null". Drives NullPtrDereferenceChecker. Propagates by def; not across
   * calls (callee param nullness is a separate question, handled by the caller's checkInstruction).
   */
  public static final String MAY_BE_NULL = "plumb.nullable.may-be-null";

  public static final Set<Trait> MAY_BE_NULL_TRAITS = EnumSet.of(Trait.PROPAGATED_BY_DECL);

  /**
   * "This variable may be 0". Drives DivideByZeroChecker. Same propagation rules as {@link
   * #MAY_BE_NULL}.
   */
  public static final String MAY_BE_ZERO = "plumb.numeric.may-be-zero";

  public static final Set<Trait> MAY_BE_ZERO_TRAITS = EnumSet.of(Trait.PROPAGATED_BY_DECL);

  // ─────── 3. Resource lifecycle ───────

  /**
   * "This value is the handle of a freshly allocated resource" ? a {@code malloc} result, a {@code
   * FileInputStream} ref. Propagates across calls so a callee that returns an opened file can carry
   * the "needs close" obligation to its caller.
   */
  public static final String ALLOC_SOURCE = "plumb.resource.alloc-source";

  public static final Set<Trait> ALLOC_SOURCE_TRAITS =
      EnumSet.of(Trait.PROPAGATED_BY_DECL, Trait.PROPAGATED_THROUGH_CALL);

  /**
   * "This call is a resource release" ? a {@code free(p)}, a {@code close()}, an {@code fclose(f)}.
   * The value carries the operand name so the consumer can match "this close releases the alloc
   * tracked at instruction I".
   */
  public static final String FREE_SINK = "plumb.resource.free-sink";

  public static final Set<Trait> FREE_SINK_TRAITS = EnumSet.of(Trait.IS_PATH_SINK);

  // ─────── 4. Pointer / array access ───────

  /**
   * "This instruction dereferences a pointer" ? a {@code Load} via GEP, a {@code *p} expression.
   * Carries the pointer operand name in the value. Combines with {@link #MAY_BE_NULL} to drive
   * NullPtrDereferenceChecker.
   */
  public static final String DEREF_SINK = "plumb.pointer.deref-sink";

  public static final Set<Trait> DEREF_SINK_TRAITS = EnumSet.of(Trait.IS_PATH_SINK);

  /**
   * "This index may be out of bounds". The value carries the array name. Combines with the index's
   * numeric range facts to drive OutOfBoundsChecker.
   */
  public static final String INDEX_SINK = "plumb.array.index-sink";

  public static final Set<Trait> INDEX_SINK_TRAITS = EnumSet.of(Trait.IS_PATH_SINK);

  // ─────── 5. Numeric hazards ───────

  /**
   * "This arithmetic may overflow". The value carries the binop description (e.g. {@code
   * "i_PLUS_1"}). Drives IntegerOverflowChecker.
   */
  public static final String NUMERIC_OVERFLOW_HAZARD = "plumb.numeric.overflow-hazard";

  public static final Set<Trait> NUMERIC_OVERFLOW_HAZARD_TRAITS =
      EnumSet.of(Trait.NOT_PROPAGATED_THROUGH_LOOP, Trait.IS_PATH_SINK);

  // ─────── 6. Sensitivity ───────

  /**
   * "This value carries sensitive data" ? credentials, tokens, PII. Same propagation as taint, but
   * consumed by SensitiveDataLeakChecker rather than a generic taint walker.
   */
  public static final String SENSITIVE_DATA = "plumb.sensitive.data";

  public static final Set<Trait> SENSITIVE_DATA_TRAITS =
      EnumSet.of(Trait.UNTRUSTED_SOURCE, Trait.PROPAGATED_BY_DECL, Trait.PROPAGATED_THROUGH_CALL);

  /**
   * "This value carries PII" ? a stricter subset of {@link #SENSITIVE_DATA}. Drives the CWE-359
   * (Exposure of Private Personal Information) variant of SensitiveDataLeakChecker. A source tagged
   * as PII implies it's also SENSITIVE_DATA, so the CWE-200 variant still fires.
   */
  public static final String SENSITIVE_PII = "plumb.sensitive.pii";

  public static final Set<Trait> SENSITIVE_PII_TRAITS =
      EnumSet.of(Trait.UNTRUSTED_SOURCE, Trait.PROPAGATED_BY_DECL, Trait.PROPAGATED_THROUGH_CALL);

  // ─────── 7. Loop induction ───────

  /**
   * "This is a loop induction variable at the point of its step block". Drives loop-bounds analysis
   * (formerly also InfiniteLoopChecker, which is now in plumb-style). Dropped on loop back-edges.
   */
  public static final String LOOP_INDUCTION = "plumb.loop.induction";

  public static final Set<Trait> LOOP_INDUCTION_TRAITS =
      EnumSet.of(Trait.NOT_PROPAGATED_THROUGH_LOOP);

  // ─────── 8. Cross-function summaries bridge ───────

  /**
   * "The value came from a parameter of an unknown callee" ? the analyzer has resolved the call but
   * cannot determine the callee's body. The caller-side checker should treat the operand as opaque.
   */
  public static final String UNMODELED_CALLEE_RET = "plumb.call.unmodeled-callee-ret";

  public static final Set<Trait> UNMODELED_CALLEE_RET_TRAITS =
      EnumSet.of(Trait.PROPAGATED_BY_DECL, Trait.PROPAGATED_THROUGH_CALL);

  /**
   * "The value of this operand was resolved at the call site by folding the callee's {@link
   * org.plumb.dfa.summary.MethodSummary} return-value shape". The fact's value field carries the
   * symbolic description of the fold result, in one of the forms
   *
   * <pre>
   *   const:&lt;literal&gt;     — the callee returned a literal
   *   param:&lt;i&gt;          — the callee returned parameter i
   *   this-field:&lt;name&gt;  — the callee returned this.&lt;name&gt;
   *   static-field:&lt;FQCN.field&gt; — the callee returned a static field
   *   new:&lt;type&gt;         — the callee returned a fresh allocation
   *   top                   ? the callee was UNKNOWN; this is the
   *                           unknown-callee fallback
   *   void                  ? the callee has no return value
   * </pre>
   *
   * The {@code SummaryApplier} writes one such fact per call; checkers read the fact and the
   * symbolic value to drive cross-function constant folding, branch pruning, and so on. The trait
   * set is {@code PROPAGATED_BY_DECL} so the fact follows the dest through later SSA def-use chains
   * inside the caller.
   */
  public static final String RETURN_VALUE_FOLDED = "plumb.call.return-value-folded";

  public static final Set<Trait> RETURN_VALUE_FOLDED_TRAITS = EnumSet.of(Trait.PROPAGATED_BY_DECL);

  /**
   * "This call has side effects recorded by the callee's {@link org.plumb.dfa.summary.SideEffect}
   * record." The fact's value field is a comma-joined set of {@code writes-this:&lt;name&gt;},
   * {@code writes-param:&lt;i&gt;}, {@code writes-static:&lt;FQCN.field&gt;}, {@code may-throw}, or
   * {@code pure} when the callee is pure. Checkers that care about caller-state invalidation (e.g.
   * {@code UnusedFieldChecker}, cross-function aliasing) read the fact to know what state may have
   * changed.
   */
  public static final String SIDE_EFFECT_RECORDED = "plumb.call.side-effect-recorded";

  public static final Set<Trait> SIDE_EFFECT_RECORDED_TRAITS = EnumSet.of(Trait.PROPAGATED_BY_DECL);

  /**
   * Build a fresh {@link FactKindRegistry} pre-populated with all 12 built-in kinds. Used by the
   * engine's default config so that the most common dataflow categories are available out of the
   * box.
   */
  public static FactKindRegistry registryWithBuiltins() {
    return buildRegistry();
  }

  private static FactKindRegistry buildRegistry() {
    return new FactKindRegistry(
        java.util.Map.ofEntries(
            java.util.Map.entry(
                UNTRUSTED_SOURCE,
                new FactKind(
                    UNTRUSTED_SOURCE,
                    "Value from outside the trust boundary",
                    "engine",
                    UNTRUSTED_SOURCE_TRAITS)),
            java.util.Map.entry(
                UNTRUSTED_SINK,
                new FactKind(
                    UNTRUSTED_SINK,
                    "Use site that consumes sensitive values",
                    "engine",
                    UNTRUSTED_SINK_TRAITS)),
            java.util.Map.entry(
                MAY_BE_NULL,
                new FactKind(MAY_BE_NULL, "Operand may be null", "engine", MAY_BE_NULL_TRAITS)),
            java.util.Map.entry(
                MAY_BE_ZERO,
                new FactKind(MAY_BE_ZERO, "Operand may be zero", "engine", MAY_BE_ZERO_TRAITS)),
            java.util.Map.entry(
                ALLOC_SOURCE,
                new FactKind(
                    ALLOC_SOURCE,
                    "Freshly allocated resource handle",
                    "engine",
                    ALLOC_SOURCE_TRAITS)),
            java.util.Map.entry(
                FREE_SINK,
                new FactKind(FREE_SINK, "Resource release call", "engine", FREE_SINK_TRAITS)),
            java.util.Map.entry(
                DEREF_SINK,
                new FactKind(
                    DEREF_SINK, "Pointer dereference use site", "engine", DEREF_SINK_TRAITS)),
            java.util.Map.entry(
                INDEX_SINK,
                new FactKind(INDEX_SINK, "Array index use site", "engine", INDEX_SINK_TRAITS)),
            java.util.Map.entry(
                NUMERIC_OVERFLOW_HAZARD,
                new FactKind(
                    NUMERIC_OVERFLOW_HAZARD,
                    "Arithmetic that may overflow",
                    "engine",
                    NUMERIC_OVERFLOW_HAZARD_TRAITS)),
            java.util.Map.entry(
                SENSITIVE_DATA,
                new FactKind(
                    SENSITIVE_DATA,
                    "Value carries sensitive data (PII, secrets)",
                    "engine",
                    SENSITIVE_DATA_TRAITS)),
            java.util.Map.entry(
                SENSITIVE_PII,
                new FactKind(
                    SENSITIVE_PII,
                    "Value carries personally identifiable information",
                    "engine",
                    SENSITIVE_PII_TRAITS)),
            java.util.Map.entry(
                LOOP_INDUCTION,
                new FactKind(
                    LOOP_INDUCTION,
                    "Loop induction variable at step site",
                    "engine",
                    LOOP_INDUCTION_TRAITS)),
            java.util.Map.entry(
                UNMODELED_CALLEE_RET,
                new FactKind(
                    UNMODELED_CALLEE_RET,
                    "Value returned from a callee whose body " + "the engine does not model",
                    "engine",
                    UNMODELED_CALLEE_RET_TRAITS)),
            java.util.Map.entry(
                RETURN_VALUE_FOLDED,
                new FactKind(
                    RETURN_VALUE_FOLDED,
                    "Value folded at the call site from the "
                        + "callee's MethodSummary return shape",
                    "engine",
                    RETURN_VALUE_FOLDED_TRAITS)),
            java.util.Map.entry(
                SIDE_EFFECT_RECORDED,
                new FactKind(
                    SIDE_EFFECT_RECORDED,
                    "Side effect recorded at the call site from "
                        + "the callee's MethodSummary side-effect record",
                    "engine",
                    SIDE_EFFECT_RECORDED_TRAITS))));
  }
}

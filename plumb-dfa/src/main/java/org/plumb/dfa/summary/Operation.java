package org.plumb.dfa.summary;

import java.util.Objects;
import org.plumb.dfa.checkers.boundary.Range;

/**
 * A single {@link TacFunction}-level operation that the {@link FunctionSummary} tracks. Every
 * implementation answers one question: "given the engine has already analysed the callee, what
 * effect does that callee have on its parameters and on its return value?" The summary of a
 * function is a multiset of {@code Operation}s grouped by parameter index (for {@code paramOps})
 * and a flat set (for {@code returnOps}).
 *
 * <h2>Why an {@code Operation} enum-style sum type</h2>
 *
 * <p>Different checks care about different effects. A taint checker wants to know whether a
 * parameter reaches a SQL sink; a resource-leak checker wants to know whether a parameter's
 * file/socket is closed; a null-deref checker wants to know whether a parameter is dereferenced.
 * Each is a different {@code Operation} subtype; the summary stores them all and lets each consumer
 * project only the operations it cares about.
 *
 * <h2>Post-order contract</h2>
 *
 * <p>Summaries are only useful if they describe a fully-analysed function. The {@link
 * org.plumb.dfa.engine.DfaEngine} iterates the program in reverse post-order so callees are always
 * summarised before their callers. When a checker wants to look up the summary of a callee, the
 * entry is guaranteed to be present (modulo cycles, which the engine breaks via the {@code
 * analyzing} set).
 */
public sealed interface Operation
    permits Operation.PassThrough,
        Operation.Sink,
        Operation.WriteToField,
        Operation.TaintPropagate,
        Operation.HasUnmodelledBehavior,
        Operation.UnmodelledCall,
        Operation.SolverZ3Predicate,
        Operation.PointsToPropagate,
        Operation.PersistsParam,
        Operation.RangeSeed {

  /**
   * The parameter just flows through the function unchanged. The most boring of the six: present,
   * but no information. Useful for distinguishing "this parameter is deliberately preserved" from
   * "this parameter is not even named". A TaintedChecker that sees {@code PassThrough} on a
   * parameter knows the parameter is visible to the caller at the call site.
   */
  record PassThrough() implements Operation {
    public static final PassThrough INSTANCE = new PassThrough();
  }

  /**
   * The parameter flows (directly or transitively) to a sink. {@code familyKey} is one of the keys
   * registered in {@link org.plumb.dfa.config.SinkFamilyRegistry} (e.g. {@code "sql.sinks"} for SQL
   * injection, {@code "command.sinks"} for OS command injection). The {@code atParamIndex} tells
   * the engine which of the sink's arguments carries the param's value (0 = first user-visible arg
   * after the receiver).
   *
   * <p>Only emitted for sinks the current engine configuration considers a defect (so a {@code
   * Sink("logging.sinks", 0)} from a debug logger isn't reported as a vulnerability).
   */
  record Sink(String familyKey, int atParamIndex) implements Operation {
    public Sink {
      Objects.requireNonNull(familyKey, "familyKey");
      if (atParamIndex < 0) {
        throw new IllegalArgumentException("atParamIndex must be >= 0, got " + atParamIndex);
      }
    }
  }

  /**
   * The parameter (or a value derived from it) is written to the function's enclosing class's
   * field. A caller of such a function can treat the field as tainted.
   *
   * <p>When a checker reports "data flows from this argument into the receiver's field", it means
   * the field is now an additional source of taint for any subsequent call on the same object.
   */
  record WriteToField(String fieldName) implements Operation {
    public WriteToField {
      Objects.requireNonNull(fieldName, "fieldName");
    }
  }

  /**
   * The parameter carries taint from a known source. {@code sourceId} matches one of the keys
   * registered in {@link org.plumb.dfa.config.FactKindRegistry} (e.g. {@code
   * "bu-0001-taint-request-param"} for a Spring {@code @RequestParam}). The {@code confidence} is a
   * soft 0..1 measure of how confident the callee was that the value truly came from the source
   * (1.0 = certain; 0.0 = speculative). The TaintedChecker can weight this against its own
   * threshold.
   *
   * <h2>taint origin</h2>
   *
   * <p>{@code origin} carries the <em>caller-side</em> taint label this value arrived with, so the
   * next hop's finding can name the real source instead of re-labelling the value as {@code
   * "param:<this function's own param>"}.
   *
   * <p>Before every function's entry boundary minted a fresh {@code param:<name>} source ({@code
   * TaintedCheckerBase#beginFunction}), so a finding emitted inside a sink method always reported
   * the sink's own parameter as the source. That is indistinguishable in the output from "taint
   * never crossed the call boundary" — which is exactly why cross-file taint looked absent even
   * though propagation worked. {@code origin} is {@code null} when the value entered as a genuinely
   * fresh source (a real input-source call, or the function-entry boundary with no known caller),
   * which preserves the old {@code param:} labelling for those cases.
   */
  record TaintPropagate(String sourceId, double confidence, String origin) implements Operation {
    /** Back-compatible 2-arg form: a taint with no known caller-side origin. */
    public TaintPropagate(String sourceId, double confidence) {
      this(sourceId, confidence, null);
    }

    public TaintPropagate {
      Objects.requireNonNull(sourceId, "sourceId");
      if (confidence < 0.0 || confidence > 1.0) {
        throw new IllegalArgumentException("confidence must be in [0, 1], got " + confidence);
      }
    }
  }

  /**
   * The function has effects the engine cannot model: a {@code native} method, a JNI entry point,
   * reflection (e.g. {@code Class.forName} + {@code Method.invoke}), or any other side effect the
   * engine has no representation for. When a function's summary contains this operation, callers
   * should treat the call as "opaque" -- anything might happen. The {@code reason} is a short
   * human-readable label such as {@code "native"}, {@code "jni"}, {@code "reflection"}, {@code
   * "unsupported-call"}.
   */
  record HasUnmodelledBehavior(String reason) implements Operation {
    public HasUnmodelledBehavior {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /**
   * The function calls a callee whose body the engine doesn't have (e.g. an unresolved library
   * function, a dynamic dispatch target that no strategy can pin down). When a function's summary
   * contains this operation, the calling checker has to decide whether to be conservative (assume
   * the worst about the unknown callee) or skip the call entirely.
   *
   * <p>The {@code calleeSig} is the best-effort signature of the unknown callee. The {@code
   * isLibrary} flag tells the consumer whether the missing body is a library (typically safe to
   * skip) or a user-code-resolution failure (typically needs the conservative branch).
   */
  record UnmodelledCall(String calleeSig, boolean isLibrary) implements Operation {
    public UnmodelledCall {
      Objects.requireNonNull(calleeSig, "calleeSig");
    }
  }

  /**
   * A path-condition predicate attached to a function's summary. When the caller-side checker sees
   * a {@code SolverZ3Predicate} in the callee's summary, it forwards the predicate to a {@link
   * org.plumb.dfa.solver.SolverZ3Bridge} along with the current fact context; the bridge returns
   * SAT / UNSAT / UNKNOWN, and the checker suppresses the finding on UNSAT (the path can't actually
   * be taken).
   *
   * <p>The predicate string is a small arithmetic / boolean DSL -- see {@code LocalSolverBridge}
   * for the exact grammar. Real Z3 integration is layered on top: a {@code Z3Bridge} wraps {@code
   * tools.aqua.z3-turnkey}'s native context, parses the same DSL into {@code BoolExpr} and asks Z3
   * to check satisfiability. The production implementation ships {@code LocalSolverBridge} (a
   * pure-Java fallback that's always available) and leaves the {@code Z3Bridge} as a future
   * addition that can drop in if the Z3 native library is loadable on the classpath.
   *
   * <p>{@code pathVars} is the set of variable names that the predicate references; the bridge
   * binds each to a concrete value (a fact's {@code value} field, in the simplest case) before
   * evaluating. A predicate that references a name not in {@code pathVars} is conservatively
   * treated as SAT -- the bridge can't know the value, so it assumes the path is reachable.
   */
  record SolverZ3Predicate(String predicate, java.util.Set<String> pathVars) implements Operation {
    public SolverZ3Predicate {
      Objects.requireNonNull(predicate, "predicate");
      pathVars = pathVars == null ? java.util.Set.of() : java.util.Set.copyOf(pathVars);
    }
  }

  /**
   * Cross-function points-to propagation. Records the per-allocation points-to set that a function
   * exposes at one of its exits: either through its return value, or through a field it writes to a
   * heap object the caller passed in.
   *
   * <p>The callee-side {@link org.plumb.dfa.points.PointsToAnalysis} populates this op at {@code
   * summarizeFunction} time; the caller-side checker queries the callee's summary via {@code
   * summary.returnOps()} or {@code summary.paramOps()} and uses the propagated allocation
   * identifiers to refine its own points-to set for the caller's dest or for subsequent field reads
   * on the caller's receiver.
   *
   * <p>The {@code allocSites} set is the symbolic alloc-site identifiers (as defined by {@link
   * org.plumb.dfa.points.PointsToAnalysis.AllocSite}). A callee that allocates internally and
   * returns the allocation encodes those alloc sites here; a caller that re-uses the return value
   * inherits the same alloc sites in its own points-to map.
   */
  record PointsToPropagate(java.util.Set<String> allocSites) implements Operation {
    public PointsToPropagate {
      Objects.requireNonNull(allocSites, "allocSites");
      allocSites = java.util.Set.copyOf(allocSites);
    }
  }

  /**
   * Cross-function JPA persistence hint. A function whose body calls {@code persist}/{@code
   * save}/{@code merge}/{@code update} (or any of the related "save the entity" methods) on the
   * value bound to its {@code paramIndex}-th parameter records this Operation in the parameter's
   * {@code paramOps} bucket. A caller-side checker (e.g. the DFA-based ORM_LOST_UPDATE checker)
   * that sees the value still being a "transient" entity after the call knows the call has, in
   * effect, persisted the entity -- even if the persistence happens one or more helper-method calls
   * down the stack.
   *
   * <p>The AST ORM_LOST_UPDATE checker only looks at the immediate function body. The DFA-augmented
   * version uses this Operation to "see through" helper wrappers such as {@code dao.save(u)} →
   * {@code entityManager.persist(u)}.
   */
  record PersistsParam(int paramIndex) implements Operation {
    public PersistsParam {
      if (paramIndex < 0) {
        throw new IllegalArgumentException("paramIndex must be >= 0, got " + paramIndex);
      }
    }
  }

  /**
   * caller-side {@link Range} seed for a parameter. When a function parameter is known to arrive in
   * a specific value range at the call site (e.g. the caller passed {@code Byte.MIN_VALUE} or the
   * parameter is the {@code data} field of a Container that was just assigned a constant), the
   * caller records this {@code RangeSeed} in the callee's summary at the matching {@code
   * paramIndex}. The callee-side {@link org.plumb.dfa.checkers.boundary.BoundaryConstraintChecker}
   * consults these seeds at {@code checkInstruction} time and meets the seeded range with the live
   * range, so a boundary check that would have fired on the BOTTOM over-approximation is correctly
   * refuted (or held against the caller if the seed is itself a violation).
   *
   * <p>The range is stored as a value object (not a symbolic expression) because {@link Range} is
   * already the engine's canonical interval representation. Callers with parametric ranges (e.g.
   * {@code 0..n-1} where {@code n} is itself a parameter) record a {@code SolverZ3Predicate}
   * instead and let the {@code SolverZ3Bridge} evaluate at the call site. {@code RangeSeed} is for
   * the common case where the seed is a known constant interval.
   */
  record RangeSeed(int paramIndex, Range range) implements Operation {
    public RangeSeed {
      Objects.requireNonNull(range, "range");
      if (paramIndex < 0) {
        throw new IllegalArgumentException("paramIndex must be >= 0, got " + paramIndex);
      }
    }
  }
}

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
        Operation.ReturnsResource,
        Operation.HasUnmodelledBehavior,
        Operation.UnmodelledCall,
        Operation.SolverZ3Predicate,
        Operation.SolverZ3Precondition,
        Operation.ReturnGuardFacts,
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
   * The function returns a FRESH RESOURCE that the caller is responsible for closing.
   *
   * <h2>Why this is not a {@link TaintPropagate}</h2>
   *
   * <p>The two operations look alike from the outside -- both are recorded on the return side and
   * both make the caller treat the call's destination as derived from the callee -- but they mean
   * opposite things:
   *
   * <ul>
   *   <li>{@code TaintPropagate} says "this value carries untrusted data". The taint checkers
   *       record it for every tainted return.
   *   <li>{@code ReturnsResource} says "this value is a freshly allocated resource and the caller
   *       owes it a close".
   * </ul>
   *
   * <p>Conflating them is not a naming problem, it is a false-positive generator. The engine keeps
   * ONE summary per function, merged across every checker, so the resource-leak checker reads the
   * taint checker's {@code TaintPropagate} ops as well as its own. A helper that returned a {@code
   * String} built from a request parameter therefore looked like a helper that returned an unclosed
   * stream, and the caller was reported as leaking it:
   *
   * <pre>{@code
   * private String enrich(String userAgent, String path) {
   *   return "ua=" + userAgent + " path=" + path;   // a String, not a resource
   * }
   *
   * void handleRequest(Request req) {
   *   String enriched = enrich(req.getHeader("User-Agent"), req.getRequestURI());
   *   // -> "Resource leak: t_t2 ... java.lang.String ... is not closed on every path"
   * }
   * }</pre>
   *
   * <p>{@code java.lang.String} has no {@code close()} and is not a resource at all, so the finding
   * was pure noise. A distinct operation type makes the two claims separable: the resource checker
   * reads only the operations that mean "resource", and the taint checkers read only the operations
   * that mean "taint".
   *
   * @param type the declared type of the returned resource, e.g. {@code "java.io.InputStream"}
   */
  record ReturnsResource(String type) implements Operation {
    public ReturnsResource {
      Objects.requireNonNull(type, "type");
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
   * The condition under which ONE of this function's tainted return values actually leaves the
   * function.
   *
   * <p>A function that hands a tainted value back does not hand it back on every path:
   *
   * <pre>
   *   int parse(BufferedReader r) {
   *     int n = Integer.parseInt(r.readLine());
   *     if (n &gt; 5 &amp;&amp; n &lt; 3) {   // a condition no value satisfies
   *       return n;                      // so THIS return never happens
   *     }
   *     return 0;                         // and this one is clean
   *   }
   * </pre>
   *
   * <p>The caller cannot see the body, so the callee has to publish the condition: the tainted
   * return happens only under {@code n > 5 && n < 3}. The caller then asks whether that condition
   * is SATISFIABLE.
   *
   * <p><b>Why the caller does NOT conjoin this with its own path condition.</b> A callee's
   * condition is about a variable the caller knows nothing about, at a point in the program the
   * caller's own guards do not describe. Conjoining the two would be a statement about two
   * different variables at two different points, and it would be UNSAT for a reason that has
   * nothing to do with the program. So this fact is EVALUATED ALONE: the caller asks "can this
   * condition hold?", and uses only the answer.
   *
   * <p><b>Why several of these are ALTERNATIVES.</b> A function with two tainted returns has two of
   * these, and the taint reaches the caller when EITHER of them can happen -- a disjunction, not a
   * conjunction. So the caller asks "is ANY of them satisfiable?", and the taint is unreachable
   * only when ALL of them are unsatisfiable.
   *
   * <p><b>Why an EMPTY fact list is meaningful.</b> It says "this tainted return happens
   * unconditionally". Without it, a function with one unconditional tainted return and one
   * conditioned one would look like it had only the conditioned one, and the caller would suppress
   * a taint that really does arrive.
   *
   * <p><b>Why the rule id is part of the fact.</b> The engine merges EVERY checker's summary for a
   * function into one, and each checker reads that merged summary. Two rules track different
   * sources, so their taint states differ, and a condition published for one rule's taint says
   * nothing about the other rule's taint. Carrying the rule id keeps the fact with the rule that
   * produced it: a rule only ever reads its OWN facts, so one rule's unreachable return can never
   * silence another rule's finding.
   *
   * <p>The facts name the CALLEE's variables. They are not renamed to the caller's names, because
   * they are never compared with the caller's own guards -- only their satisfiability is asked
   * about.
   *
   * @param ruleId the rule whose taint these facts are about
   * @param facts the conditions in force at the return site
   */
  record ReturnGuardFacts(
      String ruleId, java.util.List<org.plumb.dfa.solver.GuardPredicateTranslator.GuardFact> facts)
      implements Operation {
    public ReturnGuardFacts {
      ruleId = ruleId == null ? "" : ruleId;
      facts = facts == null ? java.util.List.of() : java.util.List.copyOf(facts);
    }
  }

  /**
   * A PRECONDITION attached to a function's summary: the condition one of its formal parameters has
   * to satisfy for the call to complete NORMALLY.
   *
   * <p>A callee that validates its argument says so by throwing:
   *
   * <pre>
   *   static String check(String s, int limit) {
   *     if (limit &lt;= 0) { throw new IllegalArgumentException(); }
   *     return s;
   *   }
   * </pre>
   *
   * <p>The caller cannot see that body, so the callee has to publish what it requires: {@code limit
   * &gt; 0}. The caller then instantiates the precondition on the argument it passes and asks the
   * solver whether that argument can also satisfy the caller's own guards. When the two contradict,
   * the call cannot complete normally on this path -- the callee throws -- so nothing the callee
   * returns reaches the caller's sink, and nothing the callee writes into the caller's variables
   * happens either.
   *
   * <p><b>Why this exists separately from {@link SolverZ3Predicate}.</b> A {@code
   * SolverZ3Predicate} says "this holds" about a variable the callee owns; a precondition says
   * "this MUST hold" about a variable the caller owns. Only the second can contradict the caller's
   * own guards, because only the second is about the same variable, at the same point in the
   * program. That is the whole reason the cross-function solver had no effect before: the two sides
   * were talking about different variables.
   *
   * <p><b>Why several preconditions for one formal are alternatives.</b> The summary cache merges
   * the summaries of same-signature functions, and it merges by set union. So two preconditions
   * recorded against one formal are two VARIANTS' requirements, and the call completes normally
   * when EITHER of them completes normally -- a disjunction, not a conjunction. The conjunction
   * inside one function is therefore done when the function records the operation: one operation
   * per formal, holding everything that function requires of it.
   *
   * <p>The predicate string is in the solver's grammar (see {@code LocalSolverBridge}); a
   * hand-written operator name would be unparseable, and the bridge answers "satisfiable" for
   * anything it cannot parse.
   *
   * @param predicate the condition, in the solver's grammar
   * @param pathVars the variables the predicate mentions -- for a precondition, the formal
   *     parameter it constrains, so the caller knows which name to substitute
   * @param requirements the same condition, STRUCTURED: the comparisons that make up the
   *     precondition, in the shape {@code GuardPredicateTranslator} reasons about. The solver needs
   *     the string; the arithmetic contradiction check needs the structure, because a contradiction
   *     over a free variable ("x &gt; 0 &amp;&amp; x &lt;= 0") is decidable by interval arithmetic
   *     but NOT by the pure-Java fallback bridge, which answers "satisfiable" for any variable it
   *     cannot bind.
   */
  record SolverZ3Precondition(
      String predicate,
      java.util.Set<String> pathVars,
      java.util.List<org.plumb.dfa.solver.GuardPredicateTranslator.GuardFact> requirements)
      implements Operation {
    public SolverZ3Precondition {
      Objects.requireNonNull(predicate, "predicate");
      pathVars = pathVars == null ? java.util.Set.of() : java.util.Set.copyOf(pathVars);
      requirements =
          requirements == null
              ? java.util.List.of()
              : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(requirements));
    }
  }

  /**
   * Cross-function points-to propagation. Records the per-allocation points-to set that a function
   * exposes at one of its exits: either through its return value, or through a field it writes to a
   * heap object the caller passed in.
   *
   * <p>The producer is {@code DfaEngine.attachReturnPointsTo}, which asks the points-to solver for
   * the function's return objects and records their labels here; the consumer is the taint
   * checker's {@code applyReturnPointsTo}, which maps the labels back onto the solver's objects and
   * refines the caller's destination with them.
   *
   * <p>The {@code allocSites} set holds the solver's own object labels ({@code site.type@context}),
   * not a second and parallel notion of allocation identity: the consumer looks the labels up in
   * the solver, so a label that no longer names a known object is dropped rather than trusted.
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

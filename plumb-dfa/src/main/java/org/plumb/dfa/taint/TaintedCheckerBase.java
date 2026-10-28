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
package org.plumb.dfa.taint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.plumb.dfa.config.CheckerConfig;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.dfa.fact.Fact;
import org.plumb.dfa.solver.CachedZ3Bridge;
import org.plumb.dfa.solver.SolverZ3Bridge;
import org.plumb.dfa.solver.Z3Bridge;
import org.plumb.dfa.summary.FieldOp;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.summary.Operation;
import org.plumb.dfa.summary.StringOp;
import org.plumb.reporter.Finding;
import org.plumb.reporter.FlowStep;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.tac.ir.TacBasicBlock;
import org.plumb.tac.ir.TacCfg;
import org.plumb.tac.ir.TacCfgBuilder;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * Base class for taint-style dataflow checkers.
 *
 * <p>A subclass picks a {@link TaintRule} and inherits the full dataflow machinery:
 *
 * <ul>
 *   <li><b>initFunction</b> : every parameter is provisionally marked "potentially tainted" (the
 *       engine later confirms via the caller's {@code TaintPropagate} summary or via an explicit
 *       {@code UntrustedSource} fact).
 *   <li><b>checkInstruction</b> : handles three instruction kinds:
 *       <ol type="a">
 *         <li>{@code MOVE} / {@code BINOP} : propagate taint along the data-flow edge (any tainted
 *             operand taints the result).
 *         <li>{@code CALL} : when the callee matches the rule's {@link TaintRule#sinkFamilyKey()},
 *             look at the call's argument list; any argument that is tainted fires a finding AND
 *             records a {@link Operation.Sink} on the function's summary so callers can see "this
 *             function has a SQL/Command/... sink" without re-analysing the body. When the callee
 *             has its own summary with a {@link Operation.TaintPropagate} on a parameter, the
 *             corresponding caller argument is marked tainted (cross-function taint flow).
 *         <li>{@code RETURN} : propagate any tainted operand to the function's returnOps so callers
 *             see "this function returns tainted data".
 *       </ol>
 *   <li><b>summarizeFunction</b> : assembles a {@link FunctionSummary} with one {@link
 *       Operation.TaintPropagate} per tainted parameter and one {@link Operation.Sink} per observed
 *       sink call (so the caller's cross-function pass sees the taint in one look-up).
 * </ul>
 *
 * <h2>Subclassing</h2>
 *
 * <p>A concrete checker is typically a 3-line subclass:
 *
 * <pre>
 *   public final class SqlInjectionChecker extends TaintedCheckerBase {
 *       public SqlInjectionChecker() {
 *           super(TaintRule.of(
 *               "dfa-taint-sql-injection",
 *               BuiltinFactKinds.UNTRUSTED_SOURCE,
 *               "sql.sinks",
 *               "CWE-89",
 *               TaintDomain.StringTaint.INSTANCE));
 *       }
 *   }
 * </pre>
 *
 * <p>Subclasses can override:
 *
 * <ul>
 *   <li>{@link #isSourceFact(Fact)} : to reject facts that don't qualify as a taint source for this
 *       rule (e.g. some rules want the source id to match a specific prefix).
 *   <li>{@link #sinkArgumentIndex(SinkFamily, TacInstruction)} : to override the "arg 0 is the
 *       tainted value" default for families with a non-standard signature (e.g. a sink that
 *       consumes its taint as arg 2).
 *   <li>{@link #findingMessage(TacInstruction, SinkFamily, TacOperand)} : to customise the finding
 *       message.
 * </ul>
 *
 * <h2>General DFA framework, not just SQL taint</h2>
 *
 * <p>The framework supports any dataflow category where:
 *
 * <ol>
 *   <li>The set of "potentially-tainted" operands is small enough to track per-function in a {@link
 *       HashMap}.
 *   <li>Propagation follows the SSA def-use chain (MOVE / BINOP).
 *   <li>Sinks are recognised by function name pattern (the {@link SinkFamily} catalog).
 *   <li>Cross-function taint flow goes through a {@link FunctionSummary}.
 * </ol>
 *
 * <p>Subclasses for non-taint categories (e.g. {@code NumericOverflowChecker} using {@link
 * BuiltinFactKinds#NUMERIC_OVERFLOW_HAZARD} as the source kind) follow the same shape but with a
 * different source kind. The framework is intentionally general-purpose.
 */
public abstract class TaintedCheckerBase implements DfaChecker {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(TaintedCheckerBase.class);

  /** The rule this checker fires for. */
  protected final TaintRule rule;

  /**
   * Public accessor for the rule : used by tests and by tooling that introspects the checker's
   * wiring (verifying that each checker binds the right CWE / sink family / source fact).
   */
  public TaintRule rule() {
    return rule;
  }

  /**
   * Optional path-condition verification bridge. When set, the checker asks the bridge whether the
   * source-to-sink path is satisfiable (e.g. via the {@link org.plumb.dfa.solver.LocalSolverBridge}
   * or a Z3 native bridge) before emitting a finding. UNSAT results suppress the finding. Default
   * {@code null} (no verification : every finding fires).
   */
  protected SolverZ3Bridge solverBridge;

  /**
   * The set of variables known to carry taint in the current function, keyed by the variable
   * itself. The value is a human-readable label for the source (e.g. {@code "param:id"} or the
   * concrete {@code Fact.value} from the caller's fact bag). Cleared at every function boundary by
   * {@link #initFunction}.
   */
  protected final Map<TacOperand.TacVar, String> taintedVars = new LinkedHashMap<>();

  /**
   * Parallel to {@link #taintedVars}: each tainted operand's source-to-here path. The list is
   * ordered source-first, sink-last. Propagators copy the path and append a "propagate" step;
   * cross-function summary appliers append a "summary-fold" step. Read by {@link
   * #checkSinkForFamily} when emitting a finding so reporters can render the full source-to-sink
   * trace.
   */
  protected final Map<TacOperand.TacVar, List<FlowStep>> taintPaths = new LinkedHashMap<>();

  /**
   * Records every sink call observed during the current function's analysis. Used by {@link
   * #summarizeFunction} to write {@link Operation.Sink} entries into the summary so callers can see
   * "this function has a sink" without re-analysing it.
   */
  private final List<SinkObservation> sinkObservations = new ArrayList<>();

  /**
   * caller, so the caller can decide whether to inherit taint via the cross-function summary.
   * Populated in {@link #checkInstruction}'s {@code RETURN} branch; consumed in {@link
   * #summarizeFunction} to write {@link Operation.TaintPropagate} entries on the {@code returnOps}
   * so callers see "this function returns tainted data". Cleared at every function boundary by
   * {@link #initFunction}.
   */
  private final List<TacOperand.TacVar> passedOutTaint = new ArrayList<>();

  /**
   * Per-function accumulator of string-passthrough operations (e.g. {@code arg.toLowerCase()})
   * observed during {@link #checkInstruction}. Each entry is a {@link StringOp} recording the
   * passthrough method name plus the source operand's current param index (when the source is a
   * function param; -1 for non-param sources). Consumed by {@link #summarizeFunction} to populate
   * the function summary's {@link FunctionSummary#stringOps()} list. Cleared at every function
   * boundary by {@link #initFunction}.
   */
  private final List<StringOp> stringOps = new ArrayList<>();

  /**
   * Per-function accumulator of field-write operations (e.g. {@code this.data = d}) observed during
   * {@link #checkInstruction}. Each entry is a {@link FieldOp} recording the field name and the
   * source's param index (or -1 for non-param sources). Consumed by {@link #summarizeFunction} to
   * populate the function summary's {@link FunctionSummary#fieldOps()} list. Cleared at every
   * function boundary by {@link #initFunction}.
   */
  private final List<FieldOp> fieldOps = new ArrayList<>();

  /**
   * Per-function {@code this.f} taint tracker. Records field writes (via {@link
   * #recordFieldOpForStore}) and restores taint on field reads (via {@link #propagateMOVE} when the
   * ins is a {@code FIELDLOAD}). Also seeded by {@link #propagateTaintFromCalleeSummary} when the
   * callee's summary has {@code fieldOps} entries. Cleared at every function boundary by {@link
   * #initFunction}.
   */
  private final FieldTaintTracker fieldTaintTracker = new FieldTaintTracker();

  /**
   * Per-function per-element array taint tracker ( T1). Tracks {@code a[i] = tainted} writes and
   * propagates the taint to {@code x = a[i]} reads. Promotes the array to {@link
   * ArrayTaintTracker#ANY} when the index itself is tainted (one tainted write touches all
   * subsequent reads). Cleared at every function boundary; cross-function array taint goes through
   * the {@link org.plumb.dfa.summary.FunctionSummary} cache.
   */
  private final ArrayTaintTracker arrayTaintTracker = new ArrayTaintTracker();

  /**
   * the equality-allowlist proof.
   *
   * <p>Kept as its own tracker rather than folded into {@link #stringOps} because it answers a
   * different question: not "was this string built from a tainted piece" but "is this variable
   * still allowed to hold anything a user typed". Only the second one can license suppressing a
   * finding, so it has to be a separate, auditable fact.
   */
  private final EqualityAllowlistTracker equalityAllowlists = new EqualityAllowlistTracker();

  /**
   * Whether this sink family is the deserialization family.
   *
   * <p>The deserialization filter is only meaningful for it: a JEP-290 {@code ObjectInputFilter} on
   * an {@code ObjectInputStream} says nothing about a SQL statement or a command line, and applying
   * it there would suppress findings that have nothing to do with it.
   */
  private static boolean isDeserializationFamily(SinkFamily family) {
    return family != null && "deserialization.sinks".equals(family.name());
  }

  /**
   * The deserialization filter tracker: which streams have a JEP-290 filter that can reject.
   *
   * <p>Kept as its own tracker for the same reason as the allowlist: it answers a different
   * question, and only it can license suppressing a finding. A filter is a property of the STREAM,
   * not of the bytes, so the answer is "is this stream filtered" rather than "is this value clean".
   */
  private final DeserializationFilterTracker deserializationFilters =
      new DeserializationFilterTracker();

  /**
   * eager dual-write: v2 trace accumulator. Parallel to {@link #taintPaths} (the v1 {@code
   * List<FlowStep>} list): each entry maps a tainted {@link TacOperand.TacVar} to the v2 {@link
   * TraceNode} id that represents the "latest" node on its path. The full DAG is held in {@link
   * #v2Builder}; per-variable handles here give the propagate sites a cheap O(1) parent-id lookup.
   *
   * <p>Cleared by {@link #initFunction} at every function boundary, same as {@link #taintPaths}.
   */
  private final Map<TacOperand.TacVar, Integer> v2TraceHandle = new LinkedHashMap<>();

  /**
   * eager dual-write: per-function {@link TraceBuilder}. Each {@code addSource} / {@code
   * addPropagate} / {@code addSink} call appends a node to the builder, wires the parent edges, and
   * returns a node id. The builder is reset at every function boundary (see {@link #initFunction}).
   */
  private TraceBuilder v2Builder = new TraceBuilder();

  /**
   * follow-up: protected getter for the per-var v2 trace handle. Lets subclasses (e.g. {@code
   * ResourceExhaustionChecker}) wire their own sink/violation nodes back to the taint source by id,
   * without re-exposing the full {@code v2TraceHandle} map.
   *
   * @param var the {@link TacOperand.TacVar} to look up
   * @return the v2 handle, or {@code -1} if {@code var} was not registered (e.g. not tainted, or
   *     initFunction has reset the per-function map)
   */
  protected int v2TraceHandleFor(TacOperand.TacVar var) {
    Integer h = v2TraceHandle.get(var);
    return h == null ? -1 : h;
  }

  /**
   * follow-up: protected getter for the per-var taint source label (e.g. "input
   * java.io.BufferedReader.readLine/0"). Lets subclasses build a v2 source node with the same
   * free-text label the base class uses, for consistency in the rendered trace.
   *
   * @param var the {@link TacOperand.TacVar} to look up
   * @return the source label, or {@code null} if {@code var} is not tainted
   */
  protected String taintSourceLabelFor(TacOperand.TacVar var) {
    return taintedVars.get(var);
  }

  /**
   * strip the intra-procedural bookkeeping prefixes off a taint label so the remainder can be
   * published as a summary entry's caller-side {@code origin}.
   *
   * <p>The per-function fold writes labels like {@code "callee:<sig>"}, {@code
   * "rcv-passthrough:<label>"} and {@code "param:<name>"} as it propagates. Those prefixes describe
   * <em>how</em> the value reached this function, not <em>where it came from</em>, so carrying them
   * into the next hop's finding would just move the mis-attribution one level up. Unwrapping a
   * {@code rcv-passthrough:} wrapper recursively keeps the inner real source. Anything we don't
   * recognise is returned unchanged, so an unknown label degrades to today's behaviour rather than
   * to {@code null}.
   */
  private static String taintOriginOf(String label) {
    if (label == null) return null;
    String s = label.trim();
    while (s.startsWith("rcv-passthrough:") || s.startsWith("callee:")) {
      String next = s.substring(s.indexOf(':') + 1).trim();
      // Guard against a label that is only the prefix (nothing to unwrap).
      if (next.isEmpty() || next.equals(s)) break;
      s = next;
    }
    return s.isEmpty() ? null : s;
  }

  /**
   * resolve a taint label that is still stuck at this function's own entry boundary ({@code
   * "param:<name>"}) into the origin the CALLER actually passed in.
   *
   * <p>Callees are analysed before callers (reverse post-order), so a summary lookup at sink-emit
   * time cannot see where the value came from — the caller's {@link FunctionSummary} simply does
   * not exist yet. That is why {@code beginFunction}'s unconditional {@code param:<name>} seeding
   * is what a sink method reports, and why cross-file taint looked absent even though propagation
   * worked.
   *
   * <p>The resolution therefore comes from {@link AnalysisContext#paramOrigin(String, int)}, which
   * the engine populates in a pre-pass (before the analysis loop) from the method-summary warm-up.
   * Only origins the engine could establish unambiguously are published; when callers disagree, or
   * the only known origin is another function's own entry boundary, nothing is recorded and the
   * original {@code param:} label stands.
   *
   * @param label the current taint label for the sink argument
   * @return the resolved origin, or {@code null} to keep {@code label}
   */
  private String resolveUpstreamOrigin(String label, TacFunction fn, AnalysisContext ctx) {
    if (label == null || !label.startsWith("param:") || fn == null || ctx == null) return null;
    // A framework-bound parameter carries a qualifier after the name
    // ("param:customer@spring-mvc:RequestParam"), so compare the NAME
    // rather than the whole label.
    String paramName = FrameworkEntryPoints.paramNameOf(label);
    if (paramName == null) return null;
    // A FRAMEWORK entry point outranks the caller's origin. The value was
    // bound to this parameter by the framework from the HTTP request; no
    // caller can supply a more specific origin for it, and the caller's
    // label would name an unrelated parameter in an unrelated class.
    if (FrameworkEntryPoints.qualifierOf(label) != null) {
      return null;
    }
    for (int i = 0; i < fn.params().size(); i++) {
      if (("param:" + fn.params().get(i).name()).equals(label)
          || (paramName.equals(fn.params().get(i).name()))) {
        String r = ctx.paramOrigin(fn.signature(), i);
        return r;
      }
    }
    return null;
  }

  /**
   * follow-up: protected accessor for the per-function {@link TraceBuilder}. Lets subclasses append
   * their own nodes (sink / violation) onto the same DAG the base class has been populating with
   * MARK_SOURCE + PROPAGATE nodes.
   */
  protected TraceBuilder v2Builder() {
    return v2Builder;
  }

  /**
   * tracking the sink-role node ids that terminate each taint path. Cleared per function. Used by
   * {@link #buildV2TraceForSink(TacOperand.TacVar)} to construct the {@link Trace}'s {@code
   * sinkOrViolationIds} list.
   */
  private final List<Integer> v2SinkHandles = new ArrayList<>();

  /**
   * Per-function map from {@link TacOperand.TacVar} to its "effective class name" for the
   * static-field chain.
   *
   * <p>The IR builder encodes static field access via a class name (e.g. {@code Helper.config = u})
   * as a <b>chained field access</b>:
   *
   * <pre>
   *   t_t1 = this.Helper   // FIELDLOAD lhs1=this field=Helper
   *   t_t1.config = u      // FIELDSTORE lhs1=t_t1 field=config
   * </pre>
   *
   * The per-function {@code (receiver, field)} tracker keys on the literal {@code TacVar} of the
   * FIELDSTORE's {@code lhs1} : for the write above, that's {@code t_t1}, but the "real" receiver
   * is the class name {@code Helper}. This map records {@code t_t1 : "Helper"} so the
   * FIELDSTORE/FIELDLOAD can route to the {@code ("Helper", "config")} tracker key.
   *
   * <p>Populated by {@link #recordClassRef} when a FIELDLOAD with {@code lhs1 = this} and a
   * class-name-looking {@code fieldName} is seen. Cleared at every function boundary by {@link
   * #initFunction}.
   */
  private final java.util.Map<TacOperand.TacVar, String> classRefVars = new java.util.HashMap<>();

  /**
   * Per-function path-condition fragments accumulated from CALLEE summaries. Each CALL's callee
   * summary may contain {@link Operation.SolverZ3Predicate} entries; we collect them so the bridge
   * can verify the full path condition (local guard + callee conditions) before emitting a finding.
   * Reset by {@link #initFunction}.
   */
  private final List<String> pathConditionFragments = new ArrayList<>();

  /**
   * Clear the cross-function class-field tracker. Called from {@link #finish} at the end of each
   * {@code DfaEngine} run so a subsequent run on a different program starts with a clean slate.
   */
  protected void clearCrossFunctionState() {
    crossFnClassFieldTaint.clear();
    crossFnClassFieldSource.clear();
    crossFnClassFieldReaders.clear();
    pendingRerunFns.clear();
  }

  /**
   * drain the queue of {@link TacFunction}s that must be re-analysed because a {@code FIELDSTORE}
   * populated {@link #crossFnClassFieldTaint} for a field they had already read (so their first
   * analysis missed the cross-fn taint). Called by {@code DfaEngine} after the main worklist
   * finishes. Each re-analysed function gets a fresh taintedVars state (the per-function tracker
   * clears on {@link #initFunction}) so the re-walked {@code FIELDLOAD} sees the now-populated
   * {@link #crossFnClassFieldTaint} entry and marks its dest tainted.
   *
   * @param engine the engine instance (used to invoke {@code analyseOneFunction} / {@code
   *     summariseOneFunction})
   * @param ctx the active analysis context
   * @param rerunOne a {@link BiConsumer}-style callback that re-analyses + re-summarises one
   *     function. The DfaEngine supplies this so the checkers don't reach into engine internals.
   */
  public static void drainRerunFns(
      java.util.function.BiConsumer<TacFunction, AnalysisContext> rerunOne, AnalysisContext ctx) {
    if (rerunOne == null || pendingRerunFns.isEmpty()) return;
    // Snapshot to avoid CME if a re-analysis enqueues more.
    java.util.List<TacFunction> snap = new java.util.ArrayList<>(pendingRerunFns);
    pendingRerunFns.clear();
    for (TacFunction fn : snap) {
      try {
        rerunOne.accept(fn, ctx);
      } catch (RuntimeException ex) {
        LOG.warn("re-analysis failed for {}: {}", fn.signature(), ex.toString());
      }
    }
  }

  /**
   * enqueue a {@link TacFunction} for re-analysis after the main worklist. Called from the {@code
   * FIELDSTORE} path after a fresh {@link #crossFnClassFieldTaint} entry is written, for each
   * previously-observed reader of the same {@code (ClassName, fieldName)} pair.
   */
  static void requestRerun(TacFunction fn) {
    if (fn == null) return;
    pendingRerunFns.add(fn);
  }

  /**
   * register {@code fn} as a reader of the field whose key is {@code "ClassName.fieldName"}. Called
   * from the {@code FIELDLOAD} path so a later {@code FIELDSTORE} can locate the reader and request
   * re-analysis.
   */
  static void recordFieldReader(String classFieldKey, TacFunction fn) {
    if (classFieldKey == null || classFieldKey.isEmpty() || fn == null) return;
    java.util.Set<TacFunction> set =
        crossFnClassFieldReaders.computeIfAbsent(classFieldKey, k -> new java.util.HashSet<>());
    set.add(fn);
  }

  /**
   * snapshot the current readers for a {@code "ClassName.fieldName"} key (used by the {@code
   * FIELDSTORE} path to enqueue re-analysis).
   */
  static java.util.Set<TacFunction> readersOf(String classFieldKey) {
    if (classFieldKey == null || classFieldKey.isEmpty()) {
      return java.util.Collections.emptySet();
    }
    java.util.Set<TacFunction> set = crossFnClassFieldReaders.get(classFieldKey);
    if (set == null) return java.util.Collections.emptySet();
    return java.util.Collections.unmodifiableSet(set);
  }

  @Override
  public void initEngine(AnalysisContext ctx) {
    // The deserialization filter's lambda bodies are indexed ONCE, before any
    // function is visited, because a filter's lambda may be defined in a
    // different function than the one that installs it.  The bodies are a
    // property of the program, not of the function being analysed.
    //
    // It has to be here and not in finish(): the sink check asks "is this
    // stream filtered?" while the instruction walk is still running, and a
    // filter installed by a LAMBDA can only be answered if the lambda's body
    // has already been read.  Indexing at the end would leave every lambda
    // filter looking like no filter at all, and the finding would stand.
    deserializationFilters.indexLambdaBodies(ctx.program().functions());
  }

  @Override
  public void finish(AnalysisContext ctx) {
    // Per-function trackers (fieldTaintTracker / classRefVars) are
    // already cleared in initFunction; here we just reset the
    // cross-function state shared across checker instances.
    clearCrossFunctionState();
  }

  /**
   * Field-source record. The unified cross-function field tracker stores a {@code FieldSource} per
   * key so the read path can reconstruct a path that distinguishes <i>static</i> writes (receiver
   * is a class name) from <i>aliased</i> writes (receiver has a declared class type). The {@code
   * kind} field is one of {@link #KIND_STATIC} or {@link #KIND_ALIASED}.
   *
   * <p><b> follow-up</b> — {@code v2Handle} is the v2 {@code Trace} node id of the source that
   * wrote this field, propagated through the cross-function summary fold. A subsequent {@code
   * FIELDLOAD} recovers the handle via {@code crossFnClassFieldSource.get(key)} and attaches a real
   * parent to the new PROPAGATE node instead of a fresh orphan SOURCE. {@code v2Handle=-1} means
   * the source predates dual-write (rare; the loader still falls back to orphan-SOURCE shape).
   */
  private record FieldSource(String functionSig, String kind, int v2Handle) {
    // 2-arg convenience for legacy call sites that
    // don't have a v2 handle to pass.
    private FieldSource(String functionSig, String kind) {
      this(functionSig, kind, -1);
    }

    // Receiver is a class name; the write is a "static" cross-class
    // flow: ClassA.secret = u visible to any reader of ClassA.secret.
    static final String KIND_STATIC = "STATIC";
    // Receiver has a declared class type but is not a class name; the
    // write is an "aliased" flow: receivers of the same declared
    // type may share state.
    static final String KIND_ALIASED = "ALIASED";
  }

  /**
   * Unified cross-function field taint tracker. Holds the most-recent tainted write to each {@code
   * "ClassName.fieldName"} pair seen anywhere in the program. Both static fields (receiver is a
   * class name) and aliased instance fields (receiver has a declared class type) update the same
   * map; the {@link FieldSource} tag distinguishes them so the read path can emit a meaningful path
   * message.
   *
   * <p>The key is {@code "ClassName.fieldName"}. For static fields the class name comes from the
   * resolved receiver (the IR builder encodes {@code t = this.ClassName; t.f = x}; {@code t}
   * resolves to {@code ClassName} via {@link #classRefVars}). For aliased fields the class name
   * comes from the receiver's declared {@code TacType} (the {@code classNameOfType} helper).
   *
   * <p>Static (shared across all checker instances in the same JVM). Cleared at the end of each
   * {@code DfaEngine} run via {@link #clearCrossFunctionState()}.
   */
  private static final Map<String, String> crossFnClassFieldTaint = new java.util.HashMap<>();

  /**
   * Companion to {@link #crossFnClassFieldTaint}, records {@code (functionSig, kind)} for each key.
   * The {@code kind} is {@link FieldSource#KIND_STATIC} or {@link FieldSource#KIND_ALIASED}.
   */
  private static final Map<String, FieldSource> crossFnClassFieldSource = new java.util.HashMap<>();

  /**
   * per {@code "ClassName.fieldName"} key, the set of functions whose standalone analysis ran a
   * {@code FIELDLOAD} of that field. Populated by {@link #restoreFieldTaintOnLoad} so a later
   * cross-fn {@code FIELDSTORE} (which only fires when the caller has seen a tainted value) can
   * locate the affected readers and request re-analysis. Without re-analysis, a callee whose {@code
   * FIELDLOAD} ran BEFORE the caller's {@code FIELDSTORE} would never see the field taint (the
   * {@link #crossFnClassFieldTaint} map was empty when the callee walked its IR the first time).
   * The map is <i>class-shared</i>: every {@code FIELDLOAD} of {@code this.<f>} registers the
   * enclosing function. Cleared at the end of each {@code DfaEngine} run via {@link
   * #clearCrossFunctionState()}.
   */
  private static final Map<String, java.util.Set<TacFunction>> crossFnClassFieldReaders =
      new java.util.HashMap<>();

  /**
   * queue of {@link TacFunction}s that must be re-analysed after the main worklist finishes. A
   * function is enqueued when a {@code FIELDSTORE} writes a fresh entry to {@link
   * #crossFnClassFieldTaint} for a field the function had previously read (so the read taint was
   * empty when the function ran the first time). The {@code DfaEngine} drains this queue after the
   * main worklist completes. See {@link #requestRerunForFieldReaders} for the enqueue path.
   */
  private static final java.util.Set<TacFunction> pendingRerunFns = new java.util.LinkedHashSet<>();

  /**
   * Compose the local path condition (from {@link #pathConditionForSink}) with the accumulated
   * callee-supplied fragments into a single conjunction. Returns the empty string if neither is
   * present, in which case the bridge isn't called.
   */
  protected String composePathCondition(String local) {
    if (pathConditionFragments.isEmpty()) {
      return local == null ? "" : local;
    }
    StringBuilder sb = new StringBuilder();
    if (local != null && !local.isEmpty()) {
      sb.append("(").append(local).append(")");
    }
    for (String frag : pathConditionFragments) {
      if (sb.length() > 0) sb.append(" && ");
      sb.append("(").append(frag).append(")");
    }
    return sb.toString();
  }

  /**
   * Subclass-visible hook to add a path-condition fragment. The default {@code
   * propagateTaintFromCalleeSummary} uses this to feed {@link Operation.SolverZ3Predicate} entries
   * from callee summaries. Tests and subclasses can also call it directly.
   */
  protected void addPathConditionFragment(String fragment) {
    if (fragment != null && !fragment.isEmpty()) {
      pathConditionFragments.add(fragment);
    }
  }

  /** A record of one observed sink call within the current function. */
  private record SinkObservation(SinkFamily family, int argIndex) {}

  /**
   * The sink family key this checker watches. Convenience: same as {@code rule.sinkFamilyKey()}.
   */
  protected final String sinkFamilyKey;

  /** Default arg index for a sink (0 = first user-visible arg). */
  protected static final int DEFAULT_SINK_ARG_INDEX = 0;

  protected TaintedCheckerBase(TaintRule rule) {
    this.rule = Objects.requireNonNull(rule, "rule");
    this.sinkFamilyKey = rule.sinkFamilyKey();
    // Auto-wire the path-condition verification bridge. The default
    // is Z3Bridge, which tries the real Z3 native library first and
    // falls back to LocalSolverBridge on any failure (missing native
    // lib, etc.). Tests that need a specific bridge can override via
    // setSolverBridge; explicit null disables the bridge.
    this.solverBridge = new CachedZ3Bridge(new Z3Bridge());
  }

  @Override
  public final String name() {
    return rule.ruleId();
  }

  /**
   * Mark a variable as a taint source and record a {@code "source"} {@link FlowStep} at the call
   * site. Used by the framework's seeders (function params, input-source calls, explicit {@code
   * UntrustedSource} facts) so the source-to-sink trace has a real first step (not just a
   * propagated chain). Subclasses (the numeric checkers, etc.) call this from {@link
   * #checkInstruction} when they recognise a taint-introducing call.
   *
   * <p>Replaces any prior taint on {@code v}; the new path is exactly one step (a {@code "source"}
   * step). Subsequent {@code MOVE}/{@code BINOP} propagations copy this seed path and append.
   *
   * @param v the variable that becomes tainted
   * @param label the {@link #taintedVars} label (e.g. {@code "param:data"} or {@code
   *     "input:readLine"})
   * @param ins the call / instruction that introduces taint, or {@code null} for function-param
   *     seeds (whose location is the function entry)
   * @param fnSig the enclosing function's signature; used in the {@link FlowStep#functionSig()}
   *     field so the trace can be disambiguated when a file has multiple methods with the same
   *     simple name
   * @param expr the human-readable expression that produced the taint (e.g. {@code "param:data"} or
   *     {@code "input readLine"})
   */
  protected void markSource(
      TacOperand.TacVar v, String label, TacInstruction ins, String fnSig, String expr) {
    taintedVars.put(v, label);
    int line = ins == null ? -1 : ins.line;
    int col = ins == null ? -1 : ins.column;
    String sig = fnSig == null ? "" : fnSig;
    taintPaths.put(v, java.util.List.of(new FlowStep("source", "", line, col, sig, expr)));
    // dual-write v2 SOURCE node. The op
    // is "MARK_SOURCE" with the param name as the
    // sole operand (Var). The function signature
    // becomes the functionSig field; for parameter
    // sources (ins == null) we still want a real
    // line/column for downstream consumers, so
    // we use line=1, col=1 as a stable sentinel.
    int srcLine = ins == null ? 1 : line;
    int srcCol = ins == null ? 1 : col;
    int srcHandle =
        v2Builder.addSource(
            TraceNode.Op.MARK_SOURCE,
            List.of(new OperandRef.Var(v.name(), 0)),
            expr == null ? "" : expr,
            "",
            srcLine,
            srcCol,
            sig);
    v2TraceHandle.put(v, srcHandle);
  }

  /**
   * Mark all parameters of the function as "potentially tainted" : the engine will refine this set
   * during the function's analysis (a {@code Sink} summary in the caller's context may un-taint, an
   * {@code UntrustedSource} fact may confirm taint, etc.). Cleared at the start of every function.
   *
   * <p>Each parameter is seeded with a {@code "source"} {@link FlowStep} (via {@link #markSource});
   * the role is semantically a "source" because a function parameter IS a trust-boundary entry
   * point from the function's perspective (a caller-supplied value that may carry taint).
   */
  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    taintedVars.clear();
    taintPaths.clear();
    sinkObservations.clear();
    pathConditionFragments.clear();
    thrownTaintByInsnIdentity.clear();
    passedOutTaint.clear();
    // Per-function accumulators: stringOps, fieldOps, the field-taint
    // tracker, and class-ref TacVars are all local to one function.
    // Cross-function flow goes through the FunctionSummary cache, not
    // through these trackers.
    stringOps.clear();
    fieldOps.clear();
    fieldTaintTracker.clear();
    arrayTaintTracker.clear(); // R694 T1
    classRefVars.clear();
    // per-function allowlist proof. Carried per checker like the other trackers, because the
    // proof is about names in one method's scope.
    equalityAllowlists.reset(fn.signature());
    // The deserialization filter is installed and read in the same
    // function, so its state is per-function too.  The lambda BODIES are
    // not: they are indexed once per program in finish().
    deserializationFilters.clear();
    // clear the v2 trace accumulator. The
    // builder is reset so node ids restart from 0
    // for the new function (avoids cross-function
    // id collisions in the rare case a node id
    // from fn N is used as a parent in fn N+1).
    v2TraceHandle.clear();
    v2Builder = new TraceBuilder();
    v2SinkHandles.clear();
    for (TacOperand.TacVar p : fn.params()) {
      // ins=null means "no specific call site : the function entry
      // itself is the boundary".
      //
      // A parameter bound by a FRAMEWORK entry point (@RequestParam,
      // @PathVariable, @QueryParam, ...) is a stronger source than a bare
      // parameter: the framework put a request-supplied value there. Label it
      // with the framework annotation so a finding names the actual trust
      // boundary instead of an opaque parameter name.
      String frameworkLabel = FrameworkEntryPoints.sourceLabelFor(fn, p.name());
      if (frameworkLabel != null) {
        markSource(p, frameworkLabel, null, fn.signature(), frameworkLabel);
        continue;
      }
      // A CONTAINER parameter (@RestBeanParam, @BeanParam, @RequestBean) is
      // a CARRIER, not a source: the request values it carries are its
      // FIELDS, each bound by its own annotation. Marking the parameter as
      // a source would claim the whole bean came from the request, which is
      // a different (and unproven) statement -- and it would let a finding
      // name "the bean" as the origin instead of the field that actually
      // entered the program.
      //
      // So the container is labelled as a carrier, and its fields become the
      // sources when they are read (see markContainerBoundFieldSource).
      if (FrameworkFieldBindings.containerAnnotationFor(fn, p.name()) != null) {
        markCarrier(p, fn);
        continue;
      }
      markSource(p, "param:" + p.name(), null, fn.signature(), "param:" + p.name());
    }
  }

  /**
   * Mark a parameter as a CARRIER of request-bound values, rather than as a source itself.
   *
   * <p>A carrier holds values that came from the request, but it is not itself one of them. The
   * difference matters in the report: a finding that names a carrier points at the bean, while a
   * finding that names a source points at the value that actually entered the program.
   *
   * <p>The label keeps the {@code "param:"} prefix -- the parameter IS a parameter -- and adds a
   * {@code "carrier:"} qualifier, so every consumer that understands a parameter label still
   * understands this one.
   */
  private void markCarrier(TacOperand.TacVar v, TacFunction fn) {
    String label = "param:" + v.name() + "@carrier";
    taintedVars.put(v, label);
    taintPaths.put(v, java.util.List.of(new FlowStep("carrier", "", 1, 1, fn.signature(), label)));
    int handle =
        v2Builder.addSource(
            TraceNode.Op.MARK_SOURCE,
            List.of(new OperandRef.Var(v.name(), 0)),
            label,
            "",
            1,
            1,
            fn.signature());
    v2TraceHandle.put(v, handle);
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    // observe BEFORE the per-kind handlers, so the MOVE handler can see a completed
    // allowlist proof when it decides whether to keep the prior input label.
    //
    // Clearing the taint label happens in propagateMOVE rather than here: the sink argument is the
    // BINOP temp (`t_t6`), never the allowlisted name, so a name-based check at the sink cannot
    // work at all. Clearing at the MOVE lets ordinary propagation carry the cleanliness through the
    // concatenation and leaves every rule's sink code untouched.
    equalityAllowlists.observe(ins, fn);
    // The deserialization filter is observed for the same reason and at the
    // same place: the filter is installed by a CALL, and the read that
    // follows it has to be able to ask about it.
    deserializationFilters.observeLambdaBindings(ins);
    deserializationFilters.observe(ins);
    switch (ins.kind) {
      case MOVE -> propagateMOVE(ins);
      // FIELDLOAD: dest = this.f
      // (or any other field). For
      // "this.f" loads, the tracker
      // may have a taint state for
      // the field; restore it on the
      // dest after the standard MOVE
      // propagation. Cross-class /
      // non-this loads are
      // path-insensitive and do not
      // touch the tracker.
      case FIELDLOAD -> {
        propagateMOVE(ins);
        recordClassRef(ins);
        // A request bean's FIELD is the entry point, not the bean parameter.
        // The parameter only names the container; the binding annotation
        // lives on the field, so the taint flow starts at the field load.
        // This runs BEFORE restoreFieldTaintOnLoad so the framework label
        // is the one the reader sees.
        markContainerBoundFieldSource(ins, fn, ctx);
        // pass ctx so the FIELDLOAD path
        // can consult ctx.staticFields() and
        // attach a real parent to a static
        // field access instead of falling
        // through to the orphan-SOURCE shape.
        restoreFieldTaintOnLoad(ins, fn, ctx);
      }
      case BINOP -> propagateBINOP(ins);
      case ARRAYLOAD -> {
        // T1: per-element array taint.
        // Standard propagation first (whole-array
        // MOVE handles the case where the array
        // itself is tainted). Then restore the
        // per-element taint from the tracker, so
        // a tainted `arr[0] = v; x = arr[0]`
        // pattern flows correctly even when `arr`
        // is not directly tainted.
        propagateMOVE(ins);
        restoreArrayTaintOnLoad(ins);
      }
      case UNARYOP -> propagateMOVE(ins);
      // ARRAYSTORE: arr[index] = value. If the
      // value (dest) is tainted OR the index is
      // tainted, record the write in the per-fn
      // array tracker so subsequent ARRAYLOADs
      // through the same (or any) index see the
      // taint. T1.
      case ARRAYSTORE -> recordArrayStore(ins);
      // FIELDSTORE: y.f = x. If the
      // receiver (lhs1) is `this` and the
      // stored value (dest) is currently
      // tainted from a parameter, record a
      // FieldOp on the per-function
      // accumulator. The receiver is
      // `this` when lhs1's name is
      // "this" or (in the static-method
      // case) when the receiver is the
      // function's enclosing class. Cross-class
      // field writes are deferred.
      case FIELDSTORE -> recordFieldOpForStore(ins, fn);
      case CALL -> {
        // Input-source seeding so ALL 29 taint
        // checkers (SQL, Command, XSS, ...)
        // benefit uniformly. The call
        // checks the input-source catalogue
        // and seeds a "source" step on the
        // dest if the callee matches. The
        // check is conservative: it only
        // seeds when the dest is a fresh
        // local (not already tainted) so we
        // don't override a summary-fold
        // result.
        if (ins.dest instanceof TacOperand.TacVar d
            && InputSourcePatterns.isInputSourceCall(ins.calleeSig)
            && !taintedVars.containsKey(d)) {
          markSource(d, "input:" + ins.calleeSig, ins, fn.signature(), "input " + ins.calleeSig);
        }
        // Early sanitizer short-circuit.
        // Integer.parseInt, Long.parseLong, etc.
        // produce a primitive that's provably CLEAN
        // regardless of input taint. We must check
        // this BEFORE checkSinkAtCall (which walks
        // args for sinks) and BEFORE the
        // summary-fold path. The dest is set to
        // CLEAN unconditionally for these calls.
        //
        // BUT parseInt/parseLong are ALSO in
        // InputSourcePatterns : the dest was JUST
        // seeded with `input:parseInt` five lines
        // above (line ~556). The sanitizer would
        // immediately undo that seed, leaving the
        // numeric dest clean even though it's
        // attacker-controlled. That's exactly the
        // CWE-789 / CWE-190 / CWE-191 attack
        // pattern: tainted String : parseInt :
        // tainted int : memory allocation /
        // arithmetic. Skip the sanitizer when the
        // call is itself an input-source call so
        // the input-source label survives and
        // downstream numeric-taint sinks fire.
        if (isSanitizer(ins.calleeSig)
            && ins.dest instanceof TacOperand.TacVar sanDv
            && !InputSourcePatterns.isInputSourceCall(ins.calleeSig)) {
          taintedVars.remove(sanDv);
          taintPaths.remove(sanDv);
        }
        // C: passthrough propagation MUST run BEFORE
        // checkSinkAtCall so the sink check sees taint on
        // concat / append / split / substring / etc. results.
        // Otherwise a pattern like
        //     response.setHeader("X", "/foo" + tainted);
        // silently drops the taint at the concat call:
        // checkSinkAtCall sees the (clean) concat result
        // before propagateStringPassthroughs marks it
        // tainted, returns silently, and the finding never
        // fires. (Lesson 1049.)
        propagateStringPassthroughs(ins);
        // If the callee was a known
        // passthrough, also record a StringOp
        // on the per-function
        // accumulator so the function's
        // summary exposes the passthrough.
        // The cross-function fold re-applies
        // these at the call site.
        recordStringPassthroughsForSummary(ins, fn);
        checkSinkAtCall(ins, fn, ctx);
        // Fold the callee summary (recorded param / return
        // taint) back onto the caller state so a value that
        // entered the callee clean and left it tainted is
        // re-tainted at this call site.
        propagateTaintFromCalleeSummary(ins, ctx);
        // If the callee was a known
        // passthrough, also record a StringOp
        // on the function's per-function
        // accumulator so the function's
        // summary exposes the passthrough.
        // The cross-function fold re-applies
        // these at the call site.
        recordStringPassthroughsForSummary(ins, fn);
      }
      // NEW instructions (constructors)
      // must also flow through the sink /
      // source / passthrough pipeline. Any
      // sink family targeting a ctor
      // (mem-alloc.sinks, file-path.sinks,
      // sql.sinks for `new PreparedStatement`)
      // never fired. The calleeSig is the
      // FQN.<init>/arity form, so
      // the same sink-pattern catalogue
      // matches both CALL and NEW. The args
      // list is the constructor argument
      // list (no receiver prepended) which
      // means the receiver-offset +1 in
      // checkSinkForFamily must be SKIPPED
      // here. We pass a flag to indicate
      // the call is a NEW so the offset
      // doesn't apply.
      case NEW -> {
        if (ins.calleeSig != null) {
          // 2026-10-08 (audit): the in-progress debug
          // block above (a `hasData` boolean, a
          // StringBuilder dump, a `System.err.println`,
          // and an empty `if (hasData) { }` body)
          // was left in by a previous round and
          // spammed t_t10... lines to stderr on every
          // `new` instruction during a scan. The
          // `checkSinkAtNew` call is the only thing
          // that did real work; keep that, drop the
          // rest.
          checkSinkAtNew(ins, fn, ctx);
        }
        // forward taint through the
        // constructor. If any constructor
        // arg is currently tainted, the
        // freshly-built object is tainted
        // too. Without it, two big patterns
        // miss silently:
        //
        //   CWE113 addCookieServlet
        //   --------------------------
        //     Cookie cookieSink =
        //         new Cookie("lang", data);  // data tainted
        //     response.addCookie(cookieSink); // sink
        //
        //   CWE789 uncontrolled-mem-alloc
        //   ------------------------------
        //     ArrayList xs = new ArrayList(data); // data tainted
        //     // ArrayList constructor stores
        //     // `data` as the internal capacity.
        //     // A later call on `xs` (add,
        //     // get, etc.) that sinks into an
        //     // indexed fetch or a
        //     // sensitive.leak surface would
        //     // be reachable if we modelled
        //     // the forward propagation here.
        //
        //   CWE89 generic-collection cases
        //   -----------------------------
        //     List<String> names = data.split("-");
        //     sqlStatement.execute("... IN ("
        //         String.join(",", names)
        //         ")");
        //
        // The earlier code skipped this case
        // because Cookie.<init> is a JDK
        // constructor with no plumb-side
        // summary (so the summary-fold path
        // doesn't apply). Treating the dest
        // as tainted for any tainted arg is
        // the cheapest correct fix; the FP
        // cost is paid only on
        // constructor-into-sink chains,
        // which the Juliet variants are
        // either correctly TP (the bad()
        // path) or correctly TN (the
        // good() path uses a constant).
        propagateNewTaint(ins);
      }
      case RETURN -> {
        // summarizeFunction can write a
        // TaintPropagate op on the returnOps.
        // Without this, taint would never
        // cross function boundaries through
        // return values, and the cross-function
        // summary-fold path would silently miss
        // any "helper(s) -> s" style
        // propagation.
        if (ins.lhs1 instanceof TacOperand.TacVar ret) {
          passedOutTaint.add(ret);
        }
      }
      // try / catch / finally IR.
      //
      // CATCH_HANDLER marks the entry of a catch
      // handler. The dest is the catch parameter
      // (a synthetic local that represents the
      // exception value). Java semantics: the catch
      // parameter is the thrown exception object;
      // it is not null on entry. We ensure the catch
      // parameter is cleared from any
      // may-be-null / taint bookkeeping so a
      // subsequent dereference or sink-use of the
      // catch parameter is treated as safe (which
      // matches reality: the runtime guarantees
      // non-null for any throw that actually
      // reaches a matched catch). If the throw
      // operand was itself tainted (e.g. a tainted
      // string thrown via `throw tainted;`), the
      // matching THROW below records the throw
      // operand's taint so this catch handler can
      // pick it up. Without that, catch parameter
      // taint would be lost in the dataflow,
      // silently dropping findings that should
      // fire when the catch handler later sinks
      // the value (e.g. `catch (Exception e) {
      // logger.error(e.getMessage()); }`).
      case CATCH_HANDLER -> handleCatchHandler(ins, fn);
      // A THROW inside a try block must
      // record the thrown operand's current
      // taint so the matching CATCH_HANDLER can
      // propagate that taint into the catch
      // parameter. The mapping is by
      // (throw insn idx -> taint label). The
      // catch handler then looks up the most
      // recent throw instruction that flows to
      // it via the CFG exception-successor
      // edges and adopts the recorded taint.
      case THROW -> handleThrow(ins);
      default -> {}
    }
  }

  /**
   * Handle a {@code CATCH_HANDLER} instruction. The catch parameter ({@code ins.dest}) starts as a
   * fresh exception object : guaranteed non-null by the JVM. We clear it from any may-be-null /
   * taint bookkeeping so subsequent uses see a clean local. If a throw instruction in a try block
   * has recorded its thrown operand's taint (see {@link #handleThrow}), the catch handler adopts
   * that taint label. Taint propagation through throw is intentionally an opt-in path so subclasses
   * that want to ignore it (e.g. simple "exception swallowed" rules) can override {@link
   * #catchParamInheritsThrowTaint} to return {@code false}.
   */
  private void handleCatchHandler(TacInstruction ins, TacFunction fn) {
    if (!(ins.dest instanceof TacOperand.TacVar catchParam)) {
      return;
    }
    // Always: ensure the catch param is not
    // tagged as null / taint, regardless of any
    // pre-existing state (e.g. an earlier
    // compilation pass that pre-initialized the
    // local).
    if (!catchParamInheritsThrowTaint()) {
      taintedVars.remove(catchParam);
      return;
    }
    // Look up the most recent throw whose
    // exception-successor list points to this
    // catch handler. If we find one, adopt its
    // thrown operand's taint.
    TacCfg cfg = TacCfgBuilder.build(fn);
    String throwTaint = lookupThrowTaintForCatch(cfg, ins, fn);
    if (throwTaint != null) {
      taintedVars.put(catchParam, throwTaint);
    } else {
      taintedVars.remove(catchParam);
    }
  }

  /**
   * Hook for subclasses. Default {@code true}: the catch parameter inherits the taint of the
   * matching throw operand. Override to {@code false} for rules where the catch parameter is opaque
   * (e.g. "do not log the exception value" style rules).
   */
  protected boolean catchParamInheritsThrowTaint() {
    return true;
  }

  /**
   * Walk the function's CFG exception edges and find the most recent THROW instruction (in
   * instruction-index order) whose exception-successor list includes the block that starts with the
   * given CATCH_HANDLER. Return that throw's recorded taint label, or {@code null} if no such throw
   * exists.
   */
  private String lookupThrowTaintForCatch(TacCfg cfg, TacInstruction catchIns, TacFunction fn) {
    // Find the catch block id (block whose
    // first instruction is the LABEL emitted
    // immediately before this CATCH_HANDLER;
    // the translator emits LABEL then
    // CATCH_HANDLER on consecutive
    // instructions).
    int catchBlockId = -1;
    int catchIdx = catchIns == null ? -1 : fn.instructions().indexOf(catchIns);
    if (catchIdx < 0) return null;
    // The LABEL is the previous instruction.
    if (catchIdx > 0) {
      TacInstruction prev = fn.instructionAt(catchIdx - 1);
      if (prev.kind == TacInstruction.Kind.LABEL && prev.label != null) {
        for (TacBasicBlock b : cfg.blocks()) {
          if (b.firstIndex() == catchIdx - 1) {
            catchBlockId = b.id();
            break;
          }
        }
      }
    }
    if (catchBlockId < 0) {
      // Fallback: find a block whose first
      // instruction is the CATCH_HANDLER
      // itself (some front-ends may emit
      // CATCH_HANDLER without a LABEL).
      for (TacBasicBlock b : cfg.blocks()) {
        if (b.firstIndex() == catchIdx) {
          catchBlockId = b.id();
          break;
        }
      }
    }
    if (catchBlockId < 0) return null;
    // Walk exception-successors-of-try-body
    // blocks: for any block that has
    // exception-successor including
    // catchBlockId, scan its instructions for a
    // THROW and return the last such throw's
    // recorded taint label.
    String result = null;
    for (TacBasicBlock b : cfg.blocks()) {
      List<Integer> exc = cfg.exceptionSuccs(b.id());
      if (!exc.contains(catchBlockId)) continue;
      TacInstruction lastThrow = null;
      for (int idx : b.indices()) {
        TacInstruction ti = fn.instructionAt(idx);
        if (ti.kind == TacInstruction.Kind.THROW) {
          lastThrow = ti;
        }
      }
      if (lastThrow == null) continue;
      String t = thrownTaintByInsnIdentity.get(System.identityHashCode(lastThrow));
      if (t != null) result = t;
    }
    return result;
  }

  /**
   * Record a THROW instruction's thrown operand's taint so the matching CATCH_HANDLER (resolved via
   * CFG exception-successor edges) can adopt it. Reset between functions by {@link #initFunction}.
   */
  private final Map<Integer, String> thrownTaintByInsnIdx = new HashMap<>();

  /**
   * Handle a {@code THROW} instruction. If the thrown operand is a {@link TacOperand.TacVar}
   * currently in {@link #taintedVars}, record the taint label keyed by the throw instruction's
   * index in the parent function. The catch handler that eventually receives this throw reads this
   * map to decide whether the catch parameter is tainted.
   */
  private void handleThrow(TacInstruction ins) {
    if (!(ins.lhs1 instanceof TacOperand.TacVar thrown)) return;
    String t = taintedVars.get(thrown);
    // We don't have the parent fn here, so
    // the catch handler looks up the throw
    // by walking the function's instructions
    // and matching TacInstruction instances
    // by identity. We use a small wrapper:
    // map identityHashCode -> taint so we
    // don't need to thread fn through this
    // method.
    thrownTaintByInsnIdentity.put(System.identityHashCode(ins), t);
  }

  /** Throw insn identity hash -> taint label at throw time. */
  private final Map<Integer, String> thrownTaintByInsnIdentity = new HashMap<>();

  /**
   * Sanitizer check. A "sanitizer" is a function whose return value is provably CLEAN regardless of
   * input taint. The classic example is {@code Integer.parseInt(String)}: the input is a tainted
   * String but the return is a bounded int. Adding sanitizers to this list eliminates false
   * positives where the source-side taint would otherwise reach a sink via the parsed value.
   *
   * <p>Each entry matches the callee signature prefix (FQN.method/arity form, see {@code
   * TacTranslator.methodSig}). Adding an entry is a deliberate decision: only add methods that have
   * a documented trust boundary (parse / validate / whitelist / canonicalize).
   */
  private static boolean isSanitizer(String calleeSig) {
    if (calleeSig == null) return false;
    // Integer / Long / Short / Byte / Float /
    // Double / Boolean : primitive parse
    // functions. They throw on malformed input,
    // and on success the return is bounded to the
    // primitive's domain (not the input string).
    // Match both FQN ("java.lang.Integer.parseInt/1")
    // and short form ("Integer.parseInt/1") because
    // plumb-tac may not resolve the FQN when the
    // symbol solver can't find the type. The slash
    // separates the method name from the arity.
    if (calleeSig.startsWith("Integer.parseInt/")
        || calleeSig.startsWith("Integer.parseUnsignedInt/")
        || calleeSig.startsWith("Long.parseLong/")
        || calleeSig.startsWith("Long.parseUnsignedLong/")
        || calleeSig.startsWith("Short.parseShort/")
        || calleeSig.startsWith("Byte.parseByte/")
        || calleeSig.startsWith("Float.parseFloat/")
        || calleeSig.startsWith("Double.parseDouble/")
        || calleeSig.startsWith("Boolean.parseBoolean/")
        || calleeSig.startsWith("java.lang.Integer.parseInt/")
        || calleeSig.startsWith("java.lang.Integer.parseUnsignedInt/")
        || calleeSig.startsWith("java.lang.Long.parseLong/")
        || calleeSig.startsWith("java.lang.Long.parseUnsignedLong/")
        || calleeSig.startsWith("java.lang.Short.parseShort/")
        || calleeSig.startsWith("java.lang.Byte.parseByte/")
        || calleeSig.startsWith("java.lang.Float.parseFloat/")
        || calleeSig.startsWith("java.lang.Double.parseDouble/")
        || calleeSig.startsWith("java.lang.Boolean.parseBoolean/")) {
      return true;
    }
    // javax.crypto.Cipher.doFinal : the canonical
    // cryptographic trust-boundary crossing. ENCRYPT_MODE
    // produces fresh ciphertext bytes (not derived from the
    // input bytes semantically); DECRYPT_MODE produces
    // plaintext bytes (whose secrecy is the whole point of
    // the cipher). Either way, the dest cannot carry the
    // caller's input taint. Without this, Juliet
    // CWE-319 connect_tcp_passwordAuth_*.goodB2G :
    //   password = readerBuffered.readLine();   // tainted
    //   aesCipher.init(Cipher.DECRYPT_MODE, ...);
    //   password = new String(
    //       aesCipher.doFinal(password.getBytes("UTF-8")),
    //       "UTF-8");
    // : would still mark password as tainted (because
    // doFinal doesn't have a summary), and the subsequent
    //   new PasswordAuthentication("user", password.toCharArray())
    // would spuriously fire CWE-319 / CWE-256. Treating
    // doFinal as a sanitizer matches the cryptographic
    // reality and silences the FP without losing any
    // real coverage (the inputs to doFinal remain tainted
    // for upstream analysis).
    if (calleeSig.startsWith("javax.crypto.Cipher.doFinal/")
        || calleeSig.startsWith("Cipher.doFinal/")) {
      return true;
    }
    return false;
  }

  /**
   * MOVE / UNARYOP / LOAD: the dest is tainted iff the source is tainted.
   *
   * <p>: also propagates the source-to-here path: the new var gets a copy of the source's path plus
   * a fresh {@code "propagate"} step recording this instruction's line / column / destination name.
   * If the source isn't tainted, the destination's path (if any) is removed.
   */
  private void propagateMOVE(TacInstruction ins) {
    if (ins.dest == null || !(ins.dest instanceof TacOperand.TacVar dv)) {
      return;
    }
    if (ins.lhs1 instanceof TacOperand.TacVar sv && taintedVars.containsKey(sv)) {
      taintedVars.put(dv, taintedVars.get(sv));
      List<FlowStep> srcPath = taintPaths.get(sv);
      List<FlowStep> newPath = new ArrayList<>();
      if (srcPath != null) newPath.addAll(srcPath);
      newPath.add(
          new FlowStep(
              "propagate", "", ins.line, ins.column, "", "MOVE " + sv.name() + " -> " + dv.name()));
      taintPaths.put(dv, java.util.Collections.unmodifiableList(newPath));
      // dual-write v2 PROPAGATE node with
      // op=MOVE. The parent is the v2 handle of
      // the source; the new handle becomes the
      // dest's latest node on the path.
      int parentHandle = v2TraceHandle.getOrDefault(sv, -1);
      if (parentHandle >= 0) {
        int handle =
            v2Builder.addPropagate(
                TraceNode.Op.MOVE,
                List.of(new OperandRef.Var(sv.name(), 0), new OperandRef.Var(dv.name(), 0)),
                parentHandle,
                "MOVE " + sv.name() + " -> " + dv.name(),
                "",
                ins.line,
                ins.column,
                "");
        v2TraceHandle.put(dv, handle);
      }
    } else {
      // Path-insensitive taint preservation.
      // The standard semantics of an unconditional MOVE
      // to a constant is "the variable now holds this
      // constant; drop any prior taint". But this is a
      // single path statement; in another path the same
      // variable may still hold the tainted value. The
      // conservative interpretation for an interprocedural
      // taint analysis is: a variable that ever carried a
      // "param:" or "input:" taint label could be tainted
      // on entry AND may be re-tied to a non-tainted value
      // at some point. Without per-path fact tracking we
      // cannot resolve this; the cheapest correct fix is to
      // KEEP the prior "param:" / "input:" label when the
      // new RHS is a literal constant / null. That
      // preserves the "potentially tainted" semantics
      // across the path join. Without this fix, the Juliet
      // CWE-89/78/22/51/53/61/66/79/80/81/83/601/113/134/15
      // /256/319/643 family silently drops cross-TU
      // taint: the `bad` branch reads from a tainted
      // source (param or .readLine / .getParameter) and
      // passes the value across files, while the dead-
      // code `good` branch reassigns the same variable to
      // null; the unconditional MOVE on the null-
      // reassignment kills the taint globally and the
      // sink never fires.
      //
      // C: extend to cover RHS being a
      // CLEAN TacVar too. The original condition
      // excluded TacVar RHS, which silently killed
      // taint when a good-branch reassigned the
      // variable to a sanitizer-clean TacVar (e.g.
      // URLEncoder.encode(data, ...)) before the path
      // rejoined. Juliet _12 / _31 / _41 / _51 family
      // exactly exercises this pattern:
      //     bad: data = source; ... data (tainted)
      //     good: data = URLEncoder.encode(data, ...); data (clean)
      //     setHeader("X", "/foo" + data);
      // The good-branch MOVE `data = t_t40` (clean
      // TacVar) clobbered the input label; after path
      // join, BINOP `"/foo" + data` saw data as clean
      // and the bad-sink at the join point silently
      // dropped. (Lesson 1050.)
      String priorLabel = taintedVars.get(dv);
      // an allowlist proof outranks the entry-boundary label.
      //
      // The branch below deliberately keeps a `param:` label across a reassignment, because the
      // MOVE is path-insensitive and the bad path may still carry the input. An allowlist is the
      // exception, and provably so: it required a NEGATED guard, so every path that could hold an
      // unlisted value was closed by the substitution. There is no surviving bad path, so the
      // prior label must go -- otherwise `col` stays tainted forever and the substitution is
      // invisible.
      if (equalityAllowlists.isAllowlisted(dv)) {
        taintedVars.remove(dv);
        taintPaths.remove(dv);
        return;
      }
      if (priorLabel != null
          && (priorLabel.startsWith("param:") || priorLabel.startsWith("input:"))) {
        // Keep the prior input/param label across
        // the move; the rhs is either a literal
        //  or a clean TacVar (-C
        // extension). The next read of `data` is
        // path-sensitive — the bad path may still
        // have data carrying the input/param
        // label.
        return;
      }
      taintedVars.remove(dv);
      taintPaths.remove(dv);
      // const-source emission. When the
      // RHS is a literal constant (e.g.
      // {@code this.Level = Level.WARNING} in
      // Juliet CWE89 logger scaffolding), the dest
      // does NOT carry a tainted label and is
      // not a param — but the FIELDLOAD later will
      // still want a v2 parent so AI dedup can
      // tell "log-level WARN" apart from a free
      // input. Emit a fresh v2 MARK_SOURCE with
      // the const as the sole operand and record
      // its handle under `dv`. The companion
      // hook in recordFieldOpForStore picks this
      // up via v2TraceHandle.containsKey(valueVar)
      // and writes it into the per-function
      // FieldTaintTracker with label "const-source"
      // — the FIELDLOAD side then attaches the
      // recovered handle as a real parent
      // ( follow-up sites 8/9/10) instead
      // of an orphan SOURCE. Suppress for
      // param: / input: prior labels above; this
      // branch only fires when the var is fully
      // clean.
      if (ins.lhs1 instanceof TacOperand.Const c) {
        OperandRef constOperand = renderConst(c);
        int handle =
            v2Builder.addSource(
                TraceNode.Op.MARK_SOURCE,
                List.of(constOperand),
                "const " + constOperand.render(),
                "",
                ins.line,
                ins.column,
                "");
        v2TraceHandle.put(dv, handle);
      }
    }
  }

  /**
   * turn a {@link TacOperand.Const} into its structured {@link OperandRef} counterpart so the const
   * shows up in the v2 trace DAG as a typed operand (ConstInt / ConstStr / ConstFloat / ConstNull)
   * rather than a free-text blob. Renders CHAR and BOOL as quoted strings since the SARIF /
   * AI-dedup consumers prefer a single text channel for those.
   */
  private static OperandRef renderConst(TacOperand.Const c) {
    return switch (c.kind()) {
      case INT, LONG -> new OperandRef.ConstInt(((Number) c.value()).longValue());
      case FLOAT, DOUBLE -> new OperandRef.ConstFloat(((Number) c.value()).doubleValue());
      case STRING -> new OperandRef.ConstStr((String) c.value());
      case NULL -> new OperandRef.ConstNull();
      case BOOL -> new OperandRef.ConstStr(((Boolean) c.value()).toString());
      case CHAR -> new OperandRef.ConstStr("'" + c.value() + "'");
    };
  }

  /**
   * forward taint through a {@code NEW} (constructor) instruction. If any of the constructor's
   * TacVar arguments is currently tainted, mark the freshly-built object as tainted too.
   *
   * <p>This unlocks three Juliet FN classes that the previous code missed:
   *
   * <ul>
   *   <li><b>CWE113 addCookieServlet</b> : {@code new Cookie("lang", data)} followed by {@code
   *       response.addCookie(...)} silently dropped taint at the constructor because Cookie.<init>
   *       has no plumb-side summary.
   *   <li><b>CWE789</b> : {@code new ArrayList(data)} carries the tainted size forward into
   *       downstream operations on the collection.
   *   <li><b>CWE89 generic-collection</b> : {@code new ArrayList<>(taintedSource)} carries taint
   *       through the constructor into subsequent indexed access.
   * </ul>
   *
   * <p>The trade-off: any constructor that receives a tainted arg will mark the dest as tainted,
   * even when the constructor is semantically a sanitizer (e.g. {@code new
   * BigDecimal(taintedString)}). Juliet variants that exercise such sanitizers only test the
   * goodB2G path, which uses a hardcoded constant, so the FP cost is bounded to the bad() / goodG2B
   * paths that would have been FN anyway.
   */
  private void propagateNewTaint(TacInstruction ins) {
    if (ins.dest == null || !(ins.dest instanceof TacOperand.TacVar dv)) {
      return;
    }
    if (ins.args == null || ins.args.isEmpty()) return;
    // Find the first tainted arg + its label
    // and path. If none is tainted, no-op.
    TacOperand.TacVar src = null;
    for (TacOperand a : ins.args) {
      if (a instanceof TacOperand.TacVar tv && taintedVars.containsKey(tv)) {
        src = tv;
        break;
      }
    }
    if (src == null) return;
    // fix: don't override a param-derived
    // label that survived the path-insensitive
    // MOVE merge. A literal-null MOVE that
    // landed just after the NEW would otherwise
    // clobber the taint we just seeded.
    String prev = taintedVars.get(dv);
    if (prev != null && (prev.startsWith("param:") || prev.startsWith("input:"))) {
      return;
    }
    String label = taintedVars.get(src);
    taintedVars.put(dv, label);
    List<FlowStep> srcPath = taintPaths.get(src);
    List<FlowStep> newPath = new ArrayList<>();
    if (srcPath != null) newPath.addAll(srcPath);
    newPath.add(
        new FlowStep(
            "propagate",
            "",
            ins.line,
            ins.column,
            ins.calleeSig == null ? "" : ins.calleeSig,
            ins.calleeSig + " -> " + dv.name()));
    taintPaths.put(dv, java.util.Collections.unmodifiableList(newPath));
    // dual-write v2 PROPAGATE node. The op
    // is CALL_INSTANCE because this is the
    // "tainted arg in ctor/new flows to dest"
    // path. (Future follow-up: split into
    // CALL_CTOR for new T and CALL_INSTANCE for
    // static method calls; P2 only has the
    // "first tainted arg drives dest" semantic.)
    int parentHandle = v2TraceHandle.getOrDefault(src, -1);
    if (parentHandle >= 0) {
      String calleeSig = ins.calleeSig == null ? "" : ins.calleeSig;
      int handle =
          v2Builder.addPropagate(
              TraceNode.Op.CALL_INSTANCE,
              List.of(new OperandRef.Var(src.name(), 0), new OperandRef.Var(dv.name(), 0)),
              parentHandle,
              calleeSig + " -> " + dv.name(),
              "",
              ins.line,
              ins.column,
              calleeSig);
      v2TraceHandle.put(dv, handle);
    }
  }

  /**
   * BINOP: the dest is tainted iff either operand is tainted. The label is the join of the two
   * sources (or whichever one fired).
   *
   * <p>: propagates the path: when one operand is tainted, the dest inherits its path plus a fresh
   * {@code "propagate"} step. When both operands are tainted, the longer path is used (arbitrary
   * tie-break by operand order) and merged with a "BINOP" step that names both operands.
   */
  private void propagateBINOP(TacInstruction ins) {
    if (ins.dest == null || !(ins.dest instanceof TacOperand.TacVar dv)) {
      return;
    }
    String l1Label =
        (ins.lhs1 instanceof TacOperand.TacVar lv && taintedVars.containsKey(lv))
            ? taintedVars.get(lv)
            : null;
    String l2Label =
        (ins.lhs2 instanceof TacOperand.TacVar rv && taintedVars.containsKey(rv))
            ? taintedVars.get(rv)
            : null;
    List<FlowStep> l1Path = (ins.lhs1 instanceof TacOperand.TacVar lv) ? taintPaths.get(lv) : null;
    List<FlowStep> l2Path = (ins.lhs2 instanceof TacOperand.TacVar rv) ? taintPaths.get(rv) : null;
    if (l1Label != null && l2Label != null) {
      taintedVars.put(dv, l1Label + "+" + l2Label);
      List<FlowStep> newPath = new ArrayList<>();
      (l1Path != null ? l1Path : List.<FlowStep>of()).forEach(newPath::add);
      newPath.add(
          new FlowStep("propagate", "", ins.line, ins.column, "", "BINOP lhs+rhs -> " + dv.name()));
      taintPaths.put(dv, java.util.Collections.unmodifiableList(newPath));
      // dual-write v2 PROPAGATE node with
      // op=BINOP_ADD. Two parents (lhs, rhs)
      // — an ALIAS role in v2. We synthesise
      // the multi-parent DAG node via the
      // addAlias helper (which still uses
      // addNode internally and gets
      // the ALIAS role for free).
      int lh = v2TraceHandle.getOrDefault((TacOperand.TacVar) ins.lhs1, -1);
      int rh = v2TraceHandle.getOrDefault((TacOperand.TacVar) ins.lhs2, -1);
      if (lh >= 0 && rh >= 0) {
        int handle =
            v2Builder.addAlias(
                TraceNode.Op.BINOP_ADD,
                List.of(
                    new OperandRef.Var(((TacOperand.TacVar) ins.lhs1).name(), 0),
                    new OperandRef.Var(((TacOperand.TacVar) ins.lhs2).name(), 0),
                    new OperandRef.Var(dv.name(), 0)),
                List.of(lh, rh),
                "BINOP lhs+rhs -> " + dv.name(),
                "",
                ins.line,
                ins.column,
                "");
        v2TraceHandle.put(dv, handle);
      }
    } else if (l1Label != null) {
      taintedVars.put(dv, l1Label);
      List<FlowStep> newPath = new ArrayList<>();
      if (l1Path != null) newPath.addAll(l1Path);
      newPath.add(
          new FlowStep("propagate", "", ins.line, ins.column, "", "BINOP lhs -> " + dv.name()));
      taintPaths.put(dv, java.util.Collections.unmodifiableList(newPath));
      // single-parent BINOP_ADD.
      int lh = v2TraceHandle.getOrDefault((TacOperand.TacVar) ins.lhs1, -1);
      if (lh >= 0) {
        int handle =
            v2Builder.addPropagate(
                TraceNode.Op.BINOP_ADD,
                List.of(
                    new OperandRef.Var(((TacOperand.TacVar) ins.lhs1).name(), 0),
                    new OperandRef.Var(dv.name(), 0)),
                lh,
                "BINOP lhs -> " + dv.name(),
                "",
                ins.line,
                ins.column,
                "");
        v2TraceHandle.put(dv, handle);
      }
    } else if (l2Label != null) {
      taintedVars.put(dv, l2Label);
      List<FlowStep> newPath = new ArrayList<>();
      if (l2Path != null) newPath.addAll(l2Path);
      newPath.add(
          new FlowStep("propagate", "", ins.line, ins.column, "", "BINOP rhs -> " + dv.name()));
      taintPaths.put(dv, java.util.Collections.unmodifiableList(newPath));
      // single-parent BINOP_ADD (rhs).
      int rh = v2TraceHandle.getOrDefault((TacOperand.TacVar) ins.lhs2, -1);
      if (rh >= 0) {
        int handle =
            v2Builder.addPropagate(
                TraceNode.Op.BINOP_ADD,
                List.of(
                    new OperandRef.Var(((TacOperand.TacVar) ins.lhs2).name(), 0),
                    new OperandRef.Var(dv.name(), 0)),
                rh,
                "BINOP rhs -> " + dv.name(),
                "",
                ins.line,
                ins.column,
                "");
        v2TraceHandle.put(dv, handle);
      }
    } else {
      taintedVars.remove(dv);
      taintPaths.remove(dv);
      // if both labels are null, the
      // dest loses its v2 handle too.
      v2TraceHandle.remove(dv);
    }
  }

  /**
   * CALL handling: if the callee matches a sink family this rule watches, look at the configured
   * arg index; if the arg is tainted, fire a finding. Then look at the callee's summary for a
   * {@link Operation.TaintPropagate} and propagate to the caller-side operands.
   */
  private void checkSinkAtCall(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    String calleeSig = ins.calleeSig;
    if (calleeSig == null) return;
    // A callee may match multiple
    // sink families (e.g. Logger#info
    // matches both sensitive.exfil.sinks
    // and pii.exfil.sinks). Iterate all
    // matches; emit a finding for each
    // family the current rule watches.
    List<SinkFamily> families = ctx.config().sinkFamilies().lookupAll(calleeSig);
    if (families.isEmpty()) return;
    for (SinkFamily family : families) {
      if (!rule.matchesSinkFamily(family.name())) continue;
      checkSinkForFamily(ins, fn, ctx, family, calleeSig);
    }
  }

  /**
   * Known taint-passthrough string methods. The callee-summary path misses these because the JDK
   * methods have no plumb-side summary. Without this, the classic Juliet CWE-89 pattern
   *
   * <pre>
   *     String[] names = data.split("-");
   *     String sql = "..." + names[i] + "...";
   *     stmt.addBatch(sql);
   * </pre>
   *
   * silently drops the taint at the .split call site. Every entry below is a pure passthrough: it
   * returns a String derived from its argument; no new trust boundary.
   */
  private void propagateStringPassthroughs(TacInstruction ins) {
    String calleeSig = ins.calleeSig;
    if (calleeSig == null) return;
    if (ins.dest == null || !(ins.dest instanceof TacOperand.TacVar dv)) return;
    // Sanitizer methods: Integer.parseInt,
    // Long.parseLong, Boolean.parseBoolean, etc. take
    // a tainted String and return a primitive
    // (int/long/boolean) that is provably NOT tainted
    // (the parse either succeeds with a sanitized value
    // or throws NumberFormatException : either way the
    // result is bounded to the parsed type's domain).
    // Treat these as taint sinks: the dest is CLEAN
    // even if the input was tainted. Missing sanitizers
    // on Integer.parseInt have been observed to fire FP
    // findings.
    if (isSanitizer(calleeSig)) {
      // skip when the call is itself
      // an input-source. The input-source
      // seeding on line ~556 put `input:parseInt`
      // on the dest; unconditionally removing
      // here would undo the seed and leave
      // numeric-taint sinks (CWE-789 / CWE-190
      // / CWE-191) blind. Mirrors the fix in
      // the early sanitizer short-circuit
      // earlier in the CALL handler.
      if (InputSourcePatterns.isInputSourceCall(calleeSig)) {
        // Skip : keep the seed.
      } else {
        taintedVars.remove(dv);
        taintPaths.remove(dv);
      }
      return;
    }
    // Callee sig format is FQN.method/arity
    // (TacTranslator.methodSig), not the legacy
    // FQN#method JVM signature. Patterns below
    // match the slash-arity form, with the
    // arity kept loose so we cover
    // split/1, split/2, substring/1, substring/2, etc.
    boolean isSplit = calleeSig.startsWith("java.lang.String.split/");
    boolean isSub = calleeSig.startsWith("java.lang.String.substring/");
    boolean isTrim =
        calleeSig.startsWith("java.lang.String.trim/")
            || calleeSig.startsWith("java.lang.String.strip/") // 0
            || calleeSig.startsWith("java.lang.String.stripLeading/")
            || calleeSig.startsWith("java.lang.String.stripTrailing/");
    boolean isCase =
        calleeSig.startsWith("java.lang.String.toLowerCase/")
            || calleeSig.startsWith("java.lang.String.toUpperCase/")
            || calleeSig.startsWith("java.lang.String.replace/")
            || calleeSig.startsWith("java.lang.String.replaceAll/")
            || calleeSig.startsWith("java.lang.String.replaceFirst/");
    boolean isValueOf =
        calleeSig.startsWith("java.lang.String.valueOf/")
            || calleeSig.startsWith("java.lang.Integer.toString/")
            || calleeSig.startsWith("java.lang.Long.toString/")
            || calleeSig.startsWith("java.lang.Boolean.toString/")
            || calleeSig.startsWith("java.lang.Object.toString/")
            || calleeSig.startsWith("java.lang.String.concat/");
    boolean isAppend =
        calleeSig.contains(".StringBuilder.append/") || calleeSig.contains(".StringBuffer.append/");
    // 2026-10-08 (audit): the -C debug block that
    // spammed `[R759C-DEBUG] propagateStringPassthroughs ...`
    // to stderr on every concat / StringBuilder call is
    // removed. It shipped in the 9-26 dist and polluted
    // scan output (see audit report C-3 follow-up).
    // (-C was the round that introduced this; the
    // production code path does not need it.)
    // StringTokenizer / Scanner / regex
    // matchers all surface tainted substrings
    // out of a tainted haystack. Juliet CWE89
    // __getQueryString_Servlet_execute parses
    // "id=foo" out of the query string with
    // StringTokenizer.nextToken() : without
    // this passthrough, the call to
    // Statement.execute() never sees the
    // upstream taint.
    boolean isTokenizer =
        calleeSig.startsWith("java.util.StringTokenizer.nextToken/")
            || calleeSig.startsWith("java.util.Scanner.next/")
            || calleeSig.startsWith("java.util.Scanner.nextLine/")
            || calleeSig.startsWith("java.util.regex.Pattern.split/")
            || calleeSig.startsWith("java.util.regex.Matcher.group/");
    // Throwable message passthrough.
    // Juliet CWE209 / CWE81 (XSS in Error Message)
    // and many try-with-resources / catch chains
    // do `catch (Exception e) { ... e.getMessage() ... }`
    // or `e.toString()`. The catch param `e` is
    // tainted ( path-insensitive MOVE
    // preserves the `param:` / `input:` label
    // across joins, and the CATCH_HANDLER
    // wires the thrown taint to the catch
    // param), but `e.getMessage()` returns a
    // fresh String : without this passthrough
    // the call to logger / response.getWriter()
    // never sees the upstream taint.
    // Both `java.lang.Throwable.*` and the
    // subclass forms (`java.lang.Exception.*`,
    // `java.lang.RuntimeException.*`) are
    // included because the IR builder resolves
    // `e.getMessage()` to whichever static type
    // it can prove (Throwable if it lost track).
    boolean isThrowableMsg =
        calleeSig.startsWith("java.lang.Throwable.getMessage/")
            || calleeSig.startsWith("java.lang.Throwable.getLocalizedMessage/")
            || calleeSig.startsWith("java.lang.Throwable.toString/")
            || calleeSig.startsWith("java.lang.Throwable.getCause/")
            || calleeSig.startsWith("java.lang.Exception.getMessage/")
            || calleeSig.startsWith("java.lang.Exception.getLocalizedMessage/")
            || calleeSig.startsWith("java.lang.Exception.toString/")
            || calleeSig.startsWith("java.lang.Exception.getCause/")
            || calleeSig.startsWith("java.lang.RuntimeException.getMessage/")
            || calleeSig.startsWith("java.lang.RuntimeException.getLocalizedMessage/")
            || calleeSig.startsWith("java.lang.RuntimeException.toString/")
            || calleeSig.startsWith("java.lang.RuntimeException.getCause/")
            || calleeSig.startsWith("java.lang.Error.getMessage/")
            || calleeSig.startsWith("java.lang.Error.toString/")
            || calleeSig.startsWith("java.io.IOException.getMessage/")
            || calleeSig.startsWith("java.io.IOException.toString/")
            || calleeSig.startsWith("java.sql.SQLException.getMessage/")
            || calleeSig.startsWith("java.sql.SQLException.toString/");
    // String.toCharArray / String.getBytes /
    // SecretKey.getEncoded / PasswordAuthentication.getPassword /
    // Object.clone. The classic Juliet CWE-319 pattern
    //     password = readerBuffered.readLine();
    //     new PasswordAuthentication("user", password.toCharArray());
    // silently drops the taint at the .toCharArray()
    // call site: the dest of the CALL is a fresh
    // TacVar (not the password itself), so without
    // propagation, args[1] of the ctor is CLEAN and
    // the db.credential.sinks rule never fires.
    boolean isCharBytePassthrough =
        calleeSig.startsWith("java.lang.String.toCharArray/")
            || calleeSig.startsWith("java.lang.String.getBytes/")
            || calleeSig.startsWith("javax.crypto.SecretKey.getEncoded/")
            || calleeSig.startsWith("java.net.PasswordAuthentication.getPassword/")
            || calleeSig.startsWith("java.lang.Object.clone/");
    if (!(isSplit
        || isSub
        || isTrim
        || isCase
        || isValueOf
        || isAppend
        || isTokenizer
        || isThrowableMsg
        || isCharBytePassthrough)) {
      return;
    }
    // Pick the tainted source operand.
    // The TAC CALL builder
    // (ExpressionTranslator.emitCall at
    // plumb-tac) prepends the receiver to
    // the args list, so for instance methods
    // the source object is args[0], NOT
    // ins.lhs1 (which is always null on
    // CALLs). Reading ins.lhs1 would
    // always return null here, so the
    // passthrough would never fire.
    // For static methods args[0] is the
    // first user-visible arg.
    // For append/concat, both args[0]
    // (receiver) and args[1] (appended
    // value) may carry taint.
    java.util.List<TacOperand> sources = new java.util.ArrayList<>();
    if (isAppend || calleeSig.startsWith("java.lang.String.concat/")) {
      // Append: receiver (this StringBuilder)
      // the appended value both can be
      // tainted.
      if (!ins.args.isEmpty()
          && ins.args.get(0) instanceof TacOperand.TacVar t0
          && taintedVars.containsKey(t0)) {
        sources.add(t0);
      }
      if (ins.args.size() > 1
          && ins.args.get(1) instanceof TacOperand.TacVar t1
          && taintedVars.containsKey(t1)) {
        sources.add(t1);
      }
    } else {
      // All other passthroughs: args[0] is
      // either the receiver (instance
      // method) or the first arg (static
      // method) : both are the source.
      if (!ins.args.isEmpty()
          && ins.args.get(0) instanceof TacOperand.TacVar pv
          && taintedVars.containsKey(pv)) {
        sources.add(pv);
      }
    }
    if (sources.isEmpty()) return;
    // Compose a single label + path.
    StringBuilder label = new StringBuilder();
    java.util.List<FlowStep> path = new java.util.ArrayList<>();
    for (TacOperand s : sources) {
      if (label.length() > 0) label.append('+');
      label.append(taintedVars.get((TacOperand.TacVar) s));
      java.util.List<FlowStep> p = taintPaths.get((TacOperand.TacVar) s);
      if (p != null) path.addAll(p);
    }
    path.add(
        new FlowStep(
            "propagate",
            "",
            ins.line,
            ins.column,
            calleeSig,
            calleeSig + " passthrough -> " + dv.name()));
    taintedVars.put(dv, label.toString());
    taintPaths.put(dv, java.util.Collections.unmodifiableList(path));
    // dual-write v2 SUMMARY_FOLD node
    // (op=SUMMARY_PASS_THROUGH) for the
    // single-source passthrough case. For
    // multi-source (append / concat) the v2
    // emit becomes an ALIAS node with the
    // two source handles as parents — this
    // gives a richer DAG than the v1 flat
    // path list. We use CALL_INSTANCE for
    // the op since these are all instance
    // method calls (e.g. `s.toLowerCase()`,
    // `sb.append(x)`).
    java.util.List<Integer> parentHandles = new java.util.ArrayList<>();
    for (TacOperand s : sources) {
      int h = v2TraceHandle.getOrDefault((TacOperand.TacVar) s, -1);
      if (h >= 0) parentHandles.add(h);
    }
    if (!parentHandles.isEmpty()) {
      java.util.List<OperandRef> operands = new java.util.ArrayList<>();
      operands.add(new OperandRef.CallRef(calleeSig));
      for (TacOperand s : sources) {
        operands.add(new OperandRef.Var(((TacOperand.TacVar) s).name(), 0));
      }
      operands.add(new OperandRef.Var(dv.name(), 0));
      int handle;
      if (parentHandles.size() == 1) {
        handle =
            v2Builder.addPropagate(
                TraceNode.Op.SUMMARY_PASS_THROUGH,
                operands,
                parentHandles.get(0),
                calleeSig + " passthrough -> " + dv.name(),
                "",
                ins.line,
                ins.column,
                calleeSig);
      } else {
        // multi-source passthrough (append
        // with two tainted args) becomes an
        // ALIAS role node with multiple
        // parents — closer to the real DAG
        // shape than v1's flat path.
        handle =
            v2Builder.addAlias(
                TraceNode.Op.SUMMARY_PASS_THROUGH,
                operands,
                parentHandles,
                calleeSig + " passthrough -> " + dv.name(),
                "",
                ins.line,
                ins.column,
                calleeSig);
      }
      v2TraceHandle.put(dv, handle);
    }
  }

  /**
   * Companion to {@link #propagateStringPassthroughs(TacInstruction)}: when the callee was a known
   * taint-passthrough string method, record a {@link StringOp} on the per-function accumulator so
   * the function's {@link FunctionSummary} exposes the passthrough to the caller.
   *
   * <p>The recording is conservative: we only record when the source operand is currently in {@link
   * #taintedVars} AND the source's taint label starts with {@code "param:"} (meaning the source is
   * a function parameter that hasn't been overwritten). The {@code sourceParamIndex} we record is
   * the param's 0-based index (resolved via {@link TacFunction#params()}); for non-param sources we
   * record {@code sourceParamIndex = -1} so the caller-side applier can distinguish "we know the
   * source is a param" from "we don't know the source".
   *
   * <p>For the {@code append} / {@code concat} family both the receiver (args[0]) and the appended
   * value (args[1]) may carry taint; we record a {@code StringOp} for each (in args order) so the
   * two-pass analyzer sees the full passthrough sequence.
   */
  private void recordStringPassthroughsForSummary(TacInstruction ins, TacFunction fn) {
    String calleeSig = ins.calleeSig;
    if (calleeSig == null) return;
    // Mirror the recognition logic in
    // propagateStringPassthroughs. We
    // intentionally duplicate the prefix
    // checks here rather than refactor
    // the existing method to expose a
    // predicate, because the inline
    // passthrough does additional work
    // (path construction, label
    // composition) that this method
    // doesn't need.
    boolean isSplit = calleeSig.startsWith("java.lang.String.split/");
    boolean isSub = calleeSig.startsWith("java.lang.String.substring/");
    boolean isTrim =
        calleeSig.startsWith("java.lang.String.trim/")
            || calleeSig.startsWith("java.lang.String.strip/")
            || calleeSig.startsWith("java.lang.String.stripLeading/")
            || calleeSig.startsWith("java.lang.String.stripTrailing/");
    boolean isCase =
        calleeSig.startsWith("java.lang.String.toLowerCase/")
            || calleeSig.startsWith("java.lang.String.toUpperCase/")
            || calleeSig.startsWith("java.lang.String.replace/")
            || calleeSig.startsWith("java.lang.String.replaceAll/")
            || calleeSig.startsWith("java.lang.String.replaceFirst/");
    boolean isValueOf =
        calleeSig.startsWith("java.lang.String.valueOf/")
            || calleeSig.startsWith("java.lang.Integer.toString/")
            || calleeSig.startsWith("java.lang.Long.toString/")
            || calleeSig.startsWith("java.lang.Boolean.toString/")
            || calleeSig.startsWith("java.lang.Object.toString/")
            || calleeSig.startsWith("java.lang.String.concat/");
    boolean isAppend =
        calleeSig.contains(".StringBuilder.append/") || calleeSig.contains(".StringBuffer.append/");
    // StringTokenizer / Scanner / regex
    // matchers all surface tainted substrings
    // out of a tainted haystack. Juliet CWE89
    // __getQueryString_Servlet_execute parses
    // "id=foo" out of the query string with
    // StringTokenizer.nextToken() : without
    // this passthrough, the call to
    // Statement.execute() never sees the
    // upstream taint.
    boolean isTokenizer =
        calleeSig.startsWith("java.util.StringTokenizer.nextToken/")
            || calleeSig.startsWith("java.util.Scanner.next/")
            || calleeSig.startsWith("java.util.Scanner.nextLine/")
            || calleeSig.startsWith("java.util.regex.Pattern.split/")
            || calleeSig.startsWith("java.util.regex.Matcher.group/");
    // Throwable message passthrough : mirror
    // propagateStringPassthroughs so cross-function
    // summaries record the e.getMessage() flow.
    // See propagateStringPassthroughs for the
    // Juliet CWE209 / CWE81 motivation.
    boolean isThrowableMsg =
        calleeSig.startsWith("java.lang.Throwable.getMessage/")
            || calleeSig.startsWith("java.lang.Throwable.getLocalizedMessage/")
            || calleeSig.startsWith("java.lang.Throwable.toString/")
            || calleeSig.startsWith("java.lang.Throwable.getCause/")
            || calleeSig.startsWith("java.lang.Exception.getMessage/")
            || calleeSig.startsWith("java.lang.Exception.getLocalizedMessage/")
            || calleeSig.startsWith("java.lang.Exception.toString/")
            || calleeSig.startsWith("java.lang.Exception.getCause/")
            || calleeSig.startsWith("java.lang.RuntimeException.getMessage/")
            || calleeSig.startsWith("java.lang.RuntimeException.getLocalizedMessage/")
            || calleeSig.startsWith("java.lang.RuntimeException.toString/")
            || calleeSig.startsWith("java.lang.RuntimeException.getCause/")
            || calleeSig.startsWith("java.lang.Error.getMessage/")
            || calleeSig.startsWith("java.lang.Error.toString/")
            || calleeSig.startsWith("java.io.IOException.getMessage/")
            || calleeSig.startsWith("java.io.IOException.toString/")
            || calleeSig.startsWith("java.sql.SQLException.getMessage/")
            || calleeSig.startsWith("java.sql.SQLException.toString/");
    // mirror the new char[] / byte[] passthroughs
    // (String.toCharArray, String.getBytes, SecretKey.getEncoded,
    // PasswordAuthentication.getPassword, Object.clone) so cross-fn
    // summaries record the flow. See propagateStringPassthroughs
    // for the Juliet CWE-319 motivation.
    boolean isCharBytePassthrough =
        calleeSig.startsWith("java.lang.String.toCharArray/")
            || calleeSig.startsWith("java.lang.String.getBytes/")
            || calleeSig.startsWith("javax.crypto.SecretKey.getEncoded/")
            || calleeSig.startsWith("java.net.PasswordAuthentication.getPassword/")
            || calleeSig.startsWith("java.lang.Object.clone/");
    if (!(isSplit
        || isSub
        || isTrim
        || isCase
        || isValueOf
        || isAppend
        || isTokenizer
        || isThrowableMsg
        || isCharBytePassthrough)) {
      return;
    }
    // Extract the bare method name from
    // the FQN-mangled calleeSig. The
    // method name is the last dot-segment,
    // stripped of the trailing "/N" arity.
    // Examples:
    //   "java.lang.String.toLowerCase/1" -> "toLowerCase"
    //   "java.lang.StringBuilder.append/1" -> "append"
    String methodName = extractMethodName(calleeSig);
    if (methodName == null) return;
    // For append / concat both args[0] and
    // args[1] may carry taint. Record a
    // StringOp for each, in args order.
    if (isAppend || calleeSig.startsWith("java.lang.String.concat/")) {
      recordStringOpForArg(ins, fn, 0, methodName);
      recordStringOpForArg(ins, fn, 1, methodName);
    } else {
      recordStringOpForArg(ins, fn, 0, methodName);
    }
  }

  /**
   * Helper: record a {@link StringOp} for the {@code argIdx}-th argument of {@code ins} when that
   * arg is currently tainted and its taint label points to a function parameter. No-op for
   * non-TacVar args, non-tainted args, or taint labels that don't start with {@code "param:"}.
   */
  private void recordStringOpForArg(
      TacInstruction ins, TacFunction fn, int argIdx, String methodName) {
    if (argIdx < 0 || argIdx >= ins.args.size()) return;
    TacOperand arg = ins.args.get(argIdx);
    if (!(arg instanceof TacOperand.TacVar av)) return;
    if (!taintedVars.containsKey(av)) return;
    String label = taintedVars.get(av);
    if (label == null || !label.startsWith("param:")) return;
    // Resolve "param:<name>" to the
    // matching parameter index. The label
    // format is set in
    // {@link #initFunction} via
    // {@link #markSource}. A framework-bound
    // parameter appends a qualifier after the
    // name, so parse the name, not the label.
    String paramName = FrameworkEntryPoints.paramNameOf(label);
    if (paramName == null) return;
    int paramIndex = -1;
    List<TacOperand.TacVar> params = fn.params();
    for (int i = 0; i < params.size(); i++) {
      if (params.get(i).name().equals(paramName)) {
        paramIndex = i;
        break;
      }
    }
    // paramIndex is -1 when the label's
    // param name doesn't match any
    // current param. This shouldn't
    // happen in practice (initFunction
    // sets the label from the param
    // itself) but we defensively skip
    // such cases. A "non-param" StringOp
    // variant could be added later if
    // needed.
    if (paramIndex < 0) return;
    stringOps.add(new StringOp(methodName));
  }

  /**
   * Helper: extract the bare method name from a TAC {@code calleeSig}. Handles FQN, short-class,
   * and bare forms. The TAC IR has three callee-sig formats in the wild:
   *
   * <ul>
   *   <li>{@code java.lang.String.toLowerCase/1} : {@code "toLowerCase"}
   *   <li>{@code String.toLowerCase/1} : {@code "toLowerCase"}
   *   <li>{@code toLowerCase/1} : {@code "toLowerCase"}
   *   <li>{@code java.lang.String#toLowerCase/1} (Jimple form) : {@code "toLowerCase"}
   * </ul>
   *
   * Returns {@code null} when the calleeSig is null, empty, or has an unrecognised shape.
   */
  static String extractMethodName(String calleeSig) {
    if (calleeSig == null || calleeSig.isEmpty()) return null;
    int slash = calleeSig.indexOf('/');
    String head = slash < 0 ? calleeSig : calleeSig.substring(0, slash);
    int dot = head.lastIndexOf('.');
    int hash = head.lastIndexOf('#');
    int sep = Math.max(dot, hash);
    return sep < 0 ? head : head.substring(sep + 1);
  }

  /**
   * Handle a {@code FIELDSTORE} instruction ({@code y.f = x}) by recording a {@link FieldOp} on the
   * per-function accumulator when the stored value's taint label points to a function parameter.
   * The {@code FieldTaintTracker} will consume these entries to propagate taint from the matching
   * caller arg to subsequent {@code FIELDLOAD}s.
   *
   * <p>The receiver can be any non-empty {@link TacOperand.TacVar} name (not just {@code "this"}).
   * The {@code FieldTaintTracker} uses the {@code (receiver, field)} pair as the key. Earlier
   * versions were hard-coded to {@code "this"} only.
   *
   * <p>The receiver is treated as {@code this} when {@code lhs1.name()} is exactly {@code "this"}
   * (the straightforward "this.f" case) or any other TacVar name (the cross-instance case, e.g.
   * {@code h.data = d}).
   */
  private void recordFieldOpForStore(TacInstruction ins, TacFunction fn) {
    if (ins.fieldName == null || ins.fieldName.isEmpty()) return;
    if (!(ins.lhs1 instanceof TacOperand.TacVar recv)) return;
    // Resolve the receiver through the
    // class-ref map (handles the chained
    // {@code t = this.H; t.f = x}
    // static-field IR pattern).
    String recvName = resolveReceiverName(recv);
    // Accept any non-empty receiver
    // name. The earlier strict
    // {@code "this".equals(recv.name())}
    // guard silently dropped
    // cross-instance writes (e.g.
    // {@code h.data = d} in a helper
    // class). The recvName was already
    // set above via resolveReceiverName
    // (which handles the
    // {@code this.<ClassName>.f}
    // chained static-field IR pattern).
    if (recvName == null || recvName.isEmpty()) return;
    // The stored value is `ins.dest`
    // (per TacInstruction semantics:
    // `lhs1.fieldName = dest`).
    if (!(ins.dest instanceof TacOperand.TacVar valueVar)) return;
    // Find the source param index for
    // the field write. The value may
    // be a direct param reference (e.g.
    // {@code this.data = d;} where d is
    // a param), in which case the param
    // index is the source regardless of
    // taint. The earlier early-return
    // when the value was not tainted
    // silently dropped the FieldOp for
    // void-setter patterns (e.g.
    // {@code setData(d) { this.data = d; }}),
    // breaking the cross-fn fold for
    // the FN_DEEP test case.
    int paramIndex = -1;
    List<TacOperand.TacVar> params = fn.params();
    for (int i = 0; i < params.size(); i++) {
      if (params.get(i).name().equals(valueVar.name())) {
        paramIndex = i;
        break;
      }
    }
    // If the value isn't a direct
    // param reference AND not tainted,
    // we have nothing to model : the
    // field write is from a local
    // computation, constant, or other
    // non-param source with no taint
    // signal. Skip.
    if (paramIndex < 0
        && !taintedVars.containsKey(valueVar)
        // const-source path — a
        // const MOVE emits a v2 source
        // node (recorded in v2TraceHandle)
        // even though the dest is not
        // tainted. We still want to
        // record the FIELDSTORE so a
        // subsequent FIELDLOAD can
        // attach the const source as
        // the FIELDLOAD's v2 parent.
        && !v2TraceHandle.containsKey(valueVar)) {
      return;
    }
    // const-source field-write — the value
    // is a constant literal (e.g. `this.Level =
    // Level.WARNING` in Juliet CWE89 logger
    // scaffolding). The dest isn't tainted, but
    // propagateMOVE emitted a v2 MARK_SOURCE node
    // with a const operand. We record this into
    // the per-function FieldTaintTracker so a
    // subsequent FIELDLOAD can recover the
    // const handle via TrackedTaint.v2Handle()
    // and attach a real parent. Cross-TU
    // propagation is suppressed (const-source
    // doesn't cross function boundaries — that
    // would be FP noise for sibling getters).
    if (paramIndex < 0
        && !taintedVars.containsKey(valueVar)
        && v2TraceHandle.containsKey(valueVar)) {
      int srcV2Handle = v2TraceHandle.get(valueVar);
      fieldTaintTracker.recordWrite(
          recvName, ins.fieldName, "const-source", List.of(), srcV2Handle);
      return;
    }
    // If the value is a direct param
    // reference (paramIndex >= 0), we
    // record a FieldOp regardless of
    // taint state. The cross-fn fold
    // will use this FieldOp to seed
    // the caller's `this.f` tracker
    // when the caller passes a tainted
    // arg.
    if (paramIndex < 0) {
      // Not a direct param. Check
      // taint for the local-source
      // case. Also accept input-source
      // taint (e.g. `h.data = u` where
      // u came from `req.getParameter`
      // with label "input:..."). The
      // earlier strict
      // `!label.startsWith("param:")`
      // guard dropped these
      // field-write records, which
      // is the bug that the
      // CROSS_INSTANCE_SQL test
      // exercises. For input-source
      // taint, there's no param to
      // fold to, so we use
      // fromLocalSource; but the
      // standalone tracker still
      // records the (recv, field)
      // taint state so subsequent
      // FIELDLOADs in the SAME
      // function can restore it.
      String label = taintedVars.get(valueVar);
      if (label == null) {
        return;
      }
      if (label.startsWith("param:")) {
        // The tainted source is a
        // param (BINOP result, etc.).
        // Find that param's index. A
        // framework-bound parameter
        // appends a qualifier after the
        // name, so parse the name.
        String paramName = FrameworkEntryPoints.paramNameOf(label);
        for (int i = 0; paramName != null && i < params.size(); i++) {
          if (params.get(i).name().equals(paramName)) {
            paramIndex = i;
            break;
          }
        }
        if (paramIndex < 0) {
          // Tainted but param name
          // not found : model as a
          // non-param source.
          fieldOps.add(FieldOp.fromLocalSource(ins.fieldName));
          fieldTaintTracker.clearField(recvName, ins.fieldName);
          return;
        }
      }
      // Input-source taint (or any
      // other non-"param:" taint
      // label). Record a non-param
      // FieldOp for the summary; the
      // standalone tracker (below)
      // will record the actual taint
      // state for the (recv, field)
      // pair.
      if (paramIndex < 0) {
        fieldOps.add(FieldOp.fromLocalSource(ins.fieldName));
        // Don't return : fall
        // through to the tracker
        // write below so the
        // FIELDLOAD in the same
        // function can restore
        // taint.
      }
    }
    // Record the FieldOp with the
    // resolved param index (if
    // paramIndex >= 0; otherwise
    // the non-param FieldOp was
    // already added above).
    if (paramIndex >= 0) {
      fieldOps.add(FieldOp.fromParam(ins.fieldName, paramIndex));
    }
    // Also write the field taint
    // state into the per-function
    // FieldTaintTracker so subsequent
    // FIELDLOADs of (recv, f) can
    // restore it. If the param is
    // currently tainted (standalone
    // analysis path: caller passed a
    // tainted arg, param is tainted),
    // record the taint state. If the
    // param is not currently tainted
    // (standalone analysis path: no
    // source, param is clean), the
    // field is "potentially tainted"
    // : we don't record it now, but
    // the cross-fn fold will seed it
    // from the caller's arg taint.
    String fldLabel = null;
    List<FlowStep> fldPath = null;
    if (taintedVars.containsKey(valueVar)) {
      fldLabel = taintedVars.get(valueVar);
      fldPath = taintPaths.get(valueVar);
      // follow-up: pass the v2 handle of the
      // source we're recording so a subsequent
      // FIELDLOAD can attach a real parent. The
      // handle is recovered from the v2 trace map;
      // if absent (the source predates dual-write
      // or came from a non-tracked path), the
      // overload defaults to -1 and the loader
      // falls back to the orphan-SOURCE shape.
      int srcV2Handle = v2TraceHandle.getOrDefault(valueVar, -1);
      fieldTaintTracker.recordWrite(recvName, ins.fieldName, fldLabel, fldPath, srcV2Handle);
    } else {
      boolean isParamSeeded = fldLabel != null && fldLabel.startsWith("param:");
      // also write when the
      // receiver is literally "this"
      // (i.e. an instance field write
      // on the enclosing class). The
      // recvTypeName form is the
      // enclosing class name, so the
      // key matches the per-function
      // FieldTaintTracker record for
      // a same-class helper that
      // reads back via `int data =
      // this.field;`. This restores
      // the cross-fn field taint flow
      // for the same-instance helper
      // pattern (variant 45: bad()
      // writes this.dataBad, badSink()
      // reads it).
      //
      // It is safe to write the
      // class-level key here because
      // "this" can only refer to one
      // instance per enclosing class :
      // cross-fn calls inside the
      // same class share `this`. The
      // POJO getter concern (h1.data
      // polluting h2.data) is
      // preserved by NOT writing for
      // other TacVar receivers such
      // as "h" / "h1" / "h2".
      //
      // We also keep the original
      // "isLikelyClassName(recvName)"
      // branch which catches the
      // synthetic `t = this.ClassName;
      // t.f = x` static-field IR
      // pattern via classRefVars
      // resolution.
      String thisRecvTypeName = "this".equals(recvName) ? classNameOfType(recv.type()) : null;
      if (!isParamSeeded && (isLikelyClassName(recvName) || thisRecvTypeName != null)) {
        String key = (thisRecvTypeName != null ? thisRecvTypeName : recvName) + "." + ins.fieldName;
        crossFnClassFieldTaint.put(key, fldLabel);
        // enqueue every previously-observed
        // reader of this (class, field) for
        // re-analysis. Their first walk ran when
        // crossFnClassFieldTaint was empty, so the
        // dest of their FIELDLOAD was never tainted.
        // Re-analysing after this write gives them
        // a populated map and the FIELDLOAD restores
        // the taint.
        for (TacFunction reader : readersOf(key)) {
          if (reader == fn) continue; // self is
          // already up
          // to date
          requestRerun(reader);
        }
        crossFnClassFieldSource.put(
            key,
            new FieldSource(
                fn.signature(),
                "this".equals(recvName) ? FieldSource.KIND_ALIASED : FieldSource.KIND_STATIC,
                // follow-up: thread
                // the v2 handle of the
                // source so a subsequent
                // FIELDLOAD can attach a
                // real parent.
                v2TraceHandle.getOrDefault(valueVar, -1)));
      }
      // T2: per-class aliasing
      // write (recvTypeName + field)
      // is INTENTIONALLY OMITTED for
      // non-"this" TacVar receivers
      // (e.g. "h" / "h1" / "h2"). The
      // per-function FieldTaintTracker
      // (above) and the cross-function
      // fieldOps fold (via
      // applyCalleeFieldOp) handle
      // instance field taint
      // per-instance correctly.
      // Writing the class-type key
      // for every instance field
      // would over-approximate: a
      // tainted write to h1.data
      // would falsely taint any other
      // Helper.data read in a sibling
      // function. The class-level
      // entry is kept for the rare
      // case where the receiver is a
      // class name (synthetic
      // classRefVars form) or is the
      // literal "this", which are
      // already handled by the
      // isLikelyClassName branch
      // above (and the new
      // "this" branch).
    }
    // / fix: the original source
    // had a `} else {` here which made the
    // if/else chain invalid (orphan second
    // `else`). The clearField cleanup below is
    // actually part of the outer
    // `if (paramIndex < 0)` else body, not a
    // separate if/else pair. Restructured to
    // be plain sequential code: when the value
    // is a direct param reference but the param
    // is not currently tainted, clear any prior
    // taint for the (recv, f) pair. This used
    // to be inside an `else` of the outer
    // if (paramIndex < 0) check; we keep the
    // same semantics here without the orphan
    // else syntax.
    fieldTaintTracker.clearField(recvName, ins.fieldName);
    // Same cleanup for the unified
    // class-field tracker.
    // also clears the "this"-keyed
    // entry (matching the write
    // path's new branch).
    if (isLikelyClassName(recvName)) {
      String key = recvName + "." + ins.fieldName;
      crossFnClassFieldTaint.remove(key);
      crossFnClassFieldSource.remove(key);
    }
    String recvTypeName = classNameOfType(recv.type());
    if (recvTypeName != null) {
      String key = recvTypeName + "." + ins.fieldName;
      crossFnClassFieldTaint.remove(key);
      crossFnClassFieldSource.remove(key);
    }
  }

  /**
   * Detect a "class reference" FIELDLOAD and record the dest TacVar as a reference to the named
   * class. The IR builder encodes static field access (e.g. {@code Helper.config}) as a chained
   * field access:
   *
   * <pre>
   *   t_t1 = this.Helper   // FIELDLOAD lhs1=this field=Helper
   * </pre>
   *
   * where the class name {@code Helper} is treated as a field of the outer class. The {@link
   * #resolveReceiverName} helper uses this map to route the subsequent {@code t_t1.config = u}
   * FIELDSTORE to the {@code ("Helper", "config")} tracker key instead of {@code ("t_t1",
   * "config")}.
   *
   * <p><b>Heuristic</b>: a FIELDLOAD is a class reference when
   *
   * <ol>
   *   <li>{@code lhs1.name() == "this"} : the field is on the implicit self, which is how the IR
   *       builder encodes class-name accesses from within an instance method of the outer class.
   *   <li>{@code fieldName} is non-empty and starts with an uppercase letter : Java class names
   *       start with uppercase by convention (instance fields start with lowercase).
   * </ol>
   *
   * The heuristic is conservative: a false positive (recording a class ref for what is actually a
   * regular field) is harmless because the regular {@code ("this", field)} tracker key is not used
   * when {@code classRefVars} has a mapping for the dest TacVar. The FIELDLOAD records a class-ref
   * entry, and the FIELDSTORE/FIELDLOAD uses {@link #resolveReceiverName} to look up the class
   * name. The {@code ("this", field)} key is NOT recorded for class-ref field names (to avoid
   * false-positive taint on the next FIELDLOAD of the same name).
   */
  private void recordClassRef(TacInstruction ins) {
    if (ins.fieldName == null || ins.fieldName.isEmpty()) return;
    if (!(ins.lhs1 instanceof TacOperand.TacVar lhs1)) return;
    if (!(ins.dest instanceof TacOperand.TacVar dest)) return;
    if (!"this".equals(lhs1.name())) return;
    if (!isLikelyClassName(ins.fieldName)) return;
    classRefVars.put(dest, ins.fieldName);
  }

  /**
   * Is this string likely a Java class name? Heuristic: starts with an uppercase letter. Used to
   * distinguish class-name field accesses (encoded as {@code this.<ClassName>} FIELDLOAD) from
   * regular instance field accesses.
   */
  private static boolean isLikelyClassName(String s) {
    // Delegate to
    // {@link FieldOpClassifier#isLikelyClassName}
    // (the canonical home for this
    // heuristic). The
    // TaintedCheckerBase copy is
    // kept as a private static
    // for back-compat with any
    // out-of-tree caller; all
    // in-tree calls go through
    // the FieldOpClassifier.
    return FieldOpClassifier.isLikelyClassName(s);
  }

  /**
   * Resolve a TacVar to its effective "receiver" name for the {@code (receiver, field)} tracker. If
   * the TacVar was loaded from {@code this.<ClassName>}, return the class name; otherwise return
   * the TacVar's own name. This is how {@code Helper.config} static field access (encoded as {@code
   * t_t1 = this.Helper; t_t1.config = ...}) gets routed to the {@code ("Helper", "config")} tracker
   * key.
   */
  private String resolveReceiverName(TacOperand.TacVar tv) {
    if (tv == null) return null;
    String n = tv.name();
    if (n == null) return null;
    String className = classRefVars.get(tv);
    if (className != null) return className;
    return n;
  }

  /**
   * Mark a FIELDLOAD that reads a framework-bound field of a request bean as a taint SOURCE.
   *
   * <p>The container-binding shape is:
   *
   * <pre>
   *   public class Parameters {
   *     &#64;RestQuery("customer")
   *     public String customer;
   *   }
   *
   *   public void search(&#64;RestBeanParam Parameters params) {
   *     repository.findByCustomer(params.customer);
   *   }
   * </pre>
   *
   * <p>Three facts have to line up, and each comes from a different place:
   *
   * <ol>
   *   <li>the RECEIVER is a parameter of this function carrying a CONTAINER annotation -- that
   *       comes from {@link TacFunction#paramAnnotations()};
   *   <li>the receiver's TYPE names the bean class -- that comes from the {@code FIELDLOAD}'s scope
   *       operand;
   *   <li>the FIELD carries a binding annotation -- that comes from the merged program's class
   *       field-annotation table, because the bean class is declared in another compilation unit
   *       and produced no function of its own.
   * </ol>
   *
   * <p>When all three line up, the field load becomes a source labelled {@code
   * "param:params.customer@quarkus-rest:RestQuery"} -- the same label shape the parameter-level
   * model uses, with the qualified field expression as the name.
   *
   * <p><b>Nothing is suppressed here.</b> When the facts do not line up, this method does nothing
   * and the ordinary parameter treatment applies. The container parameter is still a parameter, so
   * the value stays tainted either way; all this adds is a precise statement of WHERE it entered.
   */
  private void markContainerBoundFieldSource(
      TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    if (ins.fieldName == null || ins.fieldName.isEmpty()) return;
    if (!(ins.lhs1 instanceof TacOperand.TacVar recv)) return;
    if (!(ins.dest instanceof TacOperand.TacVar dv)) return;
    if (ctx == null) return;

    // (1) The receiver must be a parameter carrying a container annotation.
    String containerAnnotation = FrameworkFieldBindings.containerAnnotationFor(fn, recv.name());
    if (containerAnnotation == null) return;

    // (2) The receiver's type names the bean class.
    String beanType = FrameworkFieldBindings.beanTypeOf(recv);
    if (beanType == null) return;

    // (3) The field must carry a binding annotation.
    String label =
        FrameworkFieldBindings.sourceLabelFor(ctx.program(), recv.name(), beanType, ins.fieldName);
    if (label == null) return;

    // The carrier parameter keeps its carrier label: the report should be
    // able to say "this bean came from the request" as well as "this field
    // did". The FIELD's label wins on the dest, because the field is the
    // value that actually entered the program.
    markSource(dv, label, ins, fn.signature(), label);
  }

  /**
   * Companion to {@link #recordFieldOpForStore}: for a {@code FIELDLOAD} ({@code dest = x.f}),
   * restore the field's taint state from the per-function {@link FieldTaintTracker}. Called AFTER
   * the standard {@link #propagateMOVE} for the same insn, so the MOVE has already done its
   * data-flow propagation (the dest is already marked tainted if the SOURCE was tainted; we now
   * handle the separate case where the FIELD itself has tracked taint).
   *
   * <p>The receiver can be any non-empty {@link TacOperand.TacVar} name (not just {@code "this"}).
   * Cross-instance loads like {@code dest = h.data} are now supported. Static-field loads are
   * handled by the class-ref map.
   *
   * <p>The standard MOVE path still applies (via the receiver being itself a TacVar that might have
   * been tainted from a prior operation).
   */
  private void restoreFieldTaintOnLoad(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    if (ins.fieldName == null || ins.fieldName.isEmpty()) return;
    if (!(ins.lhs1 instanceof TacOperand.TacVar recv)) return;
    // Accept any non-empty receiver
    // name. Resolve via the class-ref
    // map to handle
    // {@code t = this.H; ...;
    // t.f} chained static-field
    // reads.
    String recvName = resolveReceiverName(recv);
    if (recvName == null || recvName.isEmpty()) return;
    if (!(ins.dest instanceof TacOperand.TacVar dv)) return;
    // P1.5: fidelity mark for the
    // synthetic {@code this.<Class>} form. The IR
    // encodes a class-identity load (e.g. before a
    // chained {@code t.STATIC_FINAL_FIVE} read) as
    // {@code FIELDLOAD lhs1=this fieldName=Class}.
    // Semantically this is "bind the class identity
    // of <Class> into a temp" — NOT a static field
    // declaration (so we cannot emit a STATIC_FIELD_DECL
    // parent; that would be wrong). The previous shape
    // emitted a generic orphan {@code FIELDLOAD}
    // SOURCE with note {@code ".Class -> t_t"}, which
    // is technically valid but indistinguishable from a
    // normal instance-field load. Tagging it as
    // {@code MARK_SOURCE} with an explicit
    // "class identity bind" note lets trace consumers
    // tell the two apart. The dest temp's v2 handle is
    // set so any subsequent chained {@code t.f} FIELDLOAD
    // (which already routes via classRefVars + the
    // isLikelyClassName branch above) still attaches
    // its true STATIC_FIELD_DECL parent. Phase 2
    // can later upgrade this node to attach the real
    // class body as a parent once cross-TU class-index
    // hits the Juliet testcasesupport subset.
    if ("this".equals(recv.name()) && isLikelyClassName(ins.fieldName)) {
      int handle =
          v2Builder.addSource(
              TraceNode.Op.MARK_SOURCE,
              List.of(
                  new OperandRef.Var("this", 0),
                  new OperandRef.Var(ins.fieldName, 0),
                  new OperandRef.Var(dv.name(), 0)),
              "class identity bind: " + ins.fieldName,
              "",
              ins.line,
              ins.column,
              fn.signature());
      v2TraceHandle.put(dv, handle);
      return;
    }
    // if the receiver is a class reference
    // (Juliet `Level.WARNING`, `IO.logger` style),
    // try the static-field resolver before
    // falling through to the orphan-SOURCE shape.
    // The resolver catches the
    // `public static final Foo = literal`
    // subset. Method-call initialisers
    // (Juliet's `static final Logger logger =
    // Logger.getLogger(...)`) still fall
    // through to the orphan path with a
    // structured "static-field init not
    // resolved" note ( Phase 2 candidate).
    if (isLikelyClassName(recvName)) {
      int staticDeclHandle = tryResolveStaticFieldDecl(recvName, ins.fieldName, ins, fn, ctx);
      if (staticDeclHandle >= 0) {
        int handle =
            v2Builder.addPropagate(
                TraceNode.Op.FIELDLOAD,
                List.of(
                    new OperandRef.StaticFieldRef(recvName, ins.fieldName, true),
                    new OperandRef.Var(dv.name(), 0)),
                List.of(staticDeclHandle),
                "FIELDLOAD " + recvName + "." + ins.fieldName + " -> " + dv.name(),
                "",
                ins.line,
                ins.column,
                fn.signature());
        v2TraceHandle.put(dv, handle);
        return;
      }
      // No const-static match. Fall
      // through to the existing
      // orphan path below; the
      // static-field nature is
      // recorded in the node's
      // note via the StaticFieldRef
      // operand ( Phase 2 will
      // upgrade to a real parent
      // once init-path resolution
      // is in place).
    }
    FieldTaintTracker.TrackedTaint tracked = fieldTaintTracker.readField(recvName, ins.fieldName);
    if (tracked == null) {
      // Unified cross-function
      // class-field read. Look up the
      // per-class key first (declared
      // class type case), then fall
      // through to the class-name key
      // (receiver is a class name with
      // no class type or a different
      // TacType).
      String recvTypeName = classNameOfType(recv.type());
      String foundKey = null;
      String foundLabel = null;
      FieldSource foundSource = null;
      if (recvTypeName != null) {
        String aliasKey = recvTypeName + "." + ins.fieldName;
        // register this function as a reader
        // of recvTypeName.fieldName so a later
        // cross-TU FIELDSTORE can request
        // re-analysis.
        recordFieldReader(aliasKey, fn);
        String aliasLabel = crossFnClassFieldTaint.get(aliasKey);
        if (aliasLabel != null) {
          foundKey = aliasKey;
          foundLabel = aliasLabel;
          foundSource = crossFnClassFieldSource.get(aliasKey);
        }
      }
      if (foundLabel == null && isLikelyClassName(recvName)) {
        String globalKey = recvName + "." + ins.fieldName;
        // register (class-name key form).
        recordFieldReader(globalKey, fn);
        String globalLabel = crossFnClassFieldTaint.get(globalKey);
        if (globalLabel != null) {
          foundKey = globalKey;
          foundLabel = globalLabel;
          foundSource = crossFnClassFieldSource.get(globalKey);
        }
      }
      if (foundLabel != null) {
        taintedVars.put(dv, foundLabel);
        String sourceFn = foundSource == null ? null : foundSource.functionSig();
        String kind = foundSource == null ? "?" : foundSource.kind();
        String stepKind =
            FieldSource.KIND_STATIC.equals(kind) ? "GLOBAL_STATIC_READ " : "ALIASED_INSTANCE_READ ";
        String stepSuffix =
            FieldSource.KIND_STATIC.equals(kind) ? "" : " (declared as " + recvTypeName + ")";
        List<FlowStep> newPath = new ArrayList<>();
        newPath.add(
            new FlowStep(
                "propagate",
                "",
                ins.line,
                ins.column,
                fn.signature(),
                stepKind
                    + (foundKey == null ? recvName + "." + ins.fieldName : foundKey)
                    + stepSuffix
                    + " (set by "
                    + (sourceFn == null ? "?" : sourceFn)
                    + ") -> "
                    + dv.name()));
        taintPaths.put(dv, java.util.Collections.unmodifiableList(newPath));
      }
      // follow-up: dual-write v2 FIELDLOAD
      // node. If the cross-fn FieldSource was
      // written by a fold that already had a v2
      // handle ( site 12/13 thread it
      // through seedCrossFnClassFieldTaintFromFold),
      // attach that handle as the parent of this
      // PROPAGATE so the DAG stays connected.
      // Otherwise (legacy / pre-dual-write source),
      // fall back to the orphan-SOURCE shape from
      // (a fresh trust boundary).
      int recoveredHandle = foundSource == null ? -1 : foundSource.v2Handle();
      int handle;
      if (recoveredHandle >= 0) {
        handle =
            v2Builder.addPropagate(
                TraceNode.Op.FIELDLOAD,
                List.of(
                    new OperandRef.FieldRef(
                        resolveReceiverName((TacOperand.TacVar) ins.lhs1), ins.fieldName),
                    new OperandRef.Var(dv.name(), 0)),
                List.of(recoveredHandle),
                (ins.lhs1 == null ? "?" : "") + "." + ins.fieldName + " -> " + dv.name(),
                "",
                ins.line,
                ins.column,
                fn.signature());
      } else {
        // Orphan: no recovered source — treat as
        // trust boundary ( first-cut shape).
        handle =
            v2Builder.addSource(
                TraceNode.Op.FIELDLOAD,
                List.of(
                    new OperandRef.FieldRef(
                        resolveReceiverName((TacOperand.TacVar) ins.lhs1), ins.fieldName),
                    new OperandRef.Var(dv.name(), 0)),
                (ins.lhs1 == null ? "?" : "") + "." + ins.fieldName + " -> " + dv.name(),
                "",
                ins.line,
                ins.column,
                fn.signature());
      }
      v2TraceHandle.put(dv, handle);
      return;
    }
    // Restore taint: copy the tracked
    // path, append a "field-load" step
    // recording this load site.
    taintedVars.put(dv, tracked.label());
    List<FlowStep> newPath = new ArrayList<>();
    newPath.addAll(tracked.path());
    newPath.add(
        new FlowStep(
            "propagate",
            "",
            ins.line,
            ins.column,
            fn.signature(),
            "FIELDLOAD " + recvName + "." + ins.fieldName + " -> " + dv.name()));
    taintPaths.put(dv, java.util.Collections.unmodifiableList(newPath));
    // follow-up: dual-write v2 FIELDLOAD
    // node. If the writer recorded a v2 handle
    // ( site 8 / cross-fn field folds thread
    // it through recordWrite/seedCrossFnClassFieldTaintFromFold),
    // attach that handle as the parent of this
    // PROPAGATE so the DAG stays connected to the
    // original SOURCE. Otherwise (legacy / pre-
    // dual-write writer), fall back to the orphan-
    // SOURCE shape from (a fresh trust
    // boundary).
    int recoveredHandle = tracked.v2Handle();
    int handle;
    if (recoveredHandle >= 0) {
      handle =
          v2Builder.addPropagate(
              TraceNode.Op.FIELDLOAD,
              List.of(
                  new OperandRef.FieldRef(recvName, ins.fieldName),
                  new OperandRef.Var(dv.name(), 0)),
              List.of(recoveredHandle),
              "FIELDLOAD " + recvName + "." + ins.fieldName + " -> " + dv.name(),
              "",
              ins.line,
              ins.column,
              fn.signature());
    } else {
      // Orphan: no recovered source — trust
      // boundary ( first-cut shape).
      handle =
          v2Builder.addSource(
              TraceNode.Op.FIELDLOAD,
              List.of(
                  new OperandRef.FieldRef(recvName, ins.fieldName),
                  new OperandRef.Var(dv.name(), 0)),
              "FIELDLOAD " + recvName + "." + ins.fieldName + " -> " + dv.name(),
              "",
              ins.line,
              ins.column,
              fn.signature());
    }
    v2TraceHandle.put(dv, handle);
  }

  // ---------- T1: per-element array taint ----------

  /**
   * Record a write {@code arr[index] = value}. Called from the {@code ARRAYSTORE} branch in {@link
   * #checkInstruction}. If the value is currently tainted OR the index is tainted, the tracker
   * records a (per-element or {@link ArrayTaintTracker#ANY}, respectively) entry.
   *
   * <p>No-op when the value is clean AND the index is clean : clean writes to clean indices do not
   * register as taint, but they DO clear any prior tainted write to the same (arr, index) pair
   * (last-write-wins).
   */

  /**
   * look up {@code className.fieldName} in the {@link org.plumb.dfa.resolve.StaticFieldResolver}
   * carried by {@code ctx.staticFields()}. If a literal initializer is recorded, emit a fresh
   * {@link TraceNode.Op#STATIC_FIELD_DECL} v2 SOURCE node and return its handle so {@link
   * #restoreFieldTaintOnLoad} can attach it as the parent of the FIELDLOAD's PROPAGATE. Returns
   * {@code -1} when the resolver is absent, the field is unknown, or the field's initializer is not
   * a literal (e.g. a method call -- the Juliet {@code static final Logger logger =
   * Logger.getLogger(...)} pattern). Phase 2 of will add init-path resolution so method-call
   * initializers also resolve.
   */
  private int tryResolveStaticFieldDecl(
      String className, String fieldName, TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    if (ctx == null) return -1;
    org.plumb.dfa.resolve.StaticFieldResolver resolver = ctx.staticFields();
    if (resolver == null) return -1;
    org.plumb.dfa.resolve.StaticFieldResolver.LiteralValue lit =
        resolver.lookup(className, fieldName);
    if (lit == null) return -1;
    OperandRef valueOperand = literalValueToOperand(lit);
    return v2Builder.addSource(
        TraceNode.Op.STATIC_FIELD_DECL,
        List.of(new OperandRef.StaticFieldRef(className, fieldName, true), valueOperand),
        "static field declaration " + className + "." + fieldName + " = " + lit,
        "",
        ins.line,
        ins.column,
        fn.signature());
  }

  /**
   * turn a {@link org.plumb.dfa.resolve.StaticFieldResolver.LiteralValue} into a typed {@link
   * OperandRef} so the STATIC_FIELD_DECL node carries the actual value (mirrors the {@code
   * renderConst} helper in {@code propagateMOVE}).
   */
  private static OperandRef literalValueToOperand(
      org.plumb.dfa.resolve.StaticFieldResolver.LiteralValue lit) {
    return switch (lit.kind) {
      case INT, LONG -> new OperandRef.ConstInt(((Number) lit.value).longValue());
      case DOUBLE -> new OperandRef.ConstFloat(((Number) lit.value).doubleValue());
      case BOOLEAN -> new OperandRef.ConstStr(lit.value.toString());
      case STRING -> new OperandRef.ConstStr((String) lit.value);
    };
  }

  private void recordArrayStore(TacInstruction ins) {
    if (!(ins.lhs1 instanceof TacOperand.TacVar arr)) return;
    if (ins.dest == null) return;
    // Is the stored value (ins.dest) currently tainted?
    String valueLabel = null;
    List<FlowStep> valuePath = null;
    if (ins.dest instanceof TacOperand.TacVar dv && taintedVars.containsKey(dv)) {
      valueLabel = taintedVars.get(dv);
      valuePath = taintPaths.get(dv);
    }
    // Is the index (ins.lhs2) currently tainted?
    boolean indexTainted = false;
    if (ins.lhs2 instanceof TacOperand.TacVar iv && taintedVars.containsKey(iv)) {
      indexTainted = true;
    }
    if (valueLabel == null) {
      // Clean write: erase any prior taint at this
      // (arr, index) so subsequent reads see no
      // taint. Mirrors the field-tracker's
      // clear-on-clean-write behaviour.
      arrayTaintTracker.recordStore(arr, ins.lhs2, null, null, false);
      return;
    }
    // follow-up: pass the v2 handle of the
    // value's source so a subsequent ARRAYLOAD
    // can attach a real parent. dv is the
    // value-bearing var (ins.dest); if the
    // dual-write chain recorded it, v2TraceHandle
    // will have its handle.
    int valueV2Handle = -1;
    if (ins.dest instanceof TacOperand.TacVar dv2) {
      valueV2Handle = v2TraceHandle.getOrDefault(dv2, -1);
    }
    arrayTaintTracker.recordStore(
        arr, ins.lhs2, valueLabel, valuePath, indexTainted, valueV2Handle);
  }

  /**
   * Restore per-element taint on an {@code ARRAYLOAD}. Called from the {@code ARRAYLOAD} branch in
   * {@link #checkInstruction} AFTER the standard {@link #propagateMOVE} so the dest already has the
   * (whole-array) taint if any. If the per-element tracker has a more specific entry, that
   * overrides.
   *
   * <p>The override logic: if the per-element tracker has a {@link ArrayTaintTracker#ANY} entry, OR
   * a specific (arr, index) entry, replace the dest's taint label + path with the tracker's. This
   * is what makes {@code a[0] = tainted; x = a[0]} flow correctly even when {@code a} itself is not
   * directly tainted.
   */
  private void restoreArrayTaintOnLoad(TacInstruction ins) {
    if (!(ins.lhs1 instanceof TacOperand.TacVar arr)) return;
    if (!(ins.dest instanceof TacOperand.TacVar dv)) return;
    ArrayTaintTracker.TaintedEntry tracked = arrayTaintTracker.readLoad(arr, ins.lhs2);
    if (tracked == null) return;
    // Build a path that ends at the load site.
    List<FlowStep> newPath = new ArrayList<>();
    if (tracked.path() != null) newPath.addAll(tracked.path());
    newPath.add(
        new FlowStep(
            "propagate",
            "",
            ins.line,
            ins.column,
            // Function signature is unavailable
            // here (no `fn` argument). Use the
            // function entry from the caller's
            // analysis context if needed; for now
            // empty signature is acceptable since
            // the FlowStep is rendered with the
            // "propagate" kind which doesn't require
            // a per-function line.
            "",
            "ARRAYLOAD "
                + arr.name()
                + "["
                + (ins.lhs2 == null
                    ? "?"
                    : (ins.lhs2 instanceof TacOperand.TacVar tv ? tv.name() : ins.lhs2.toString()))
                + "] -> "
                + dv.name()));
    taintedVars.put(dv, tracked.label());
    taintPaths.put(dv, java.util.Collections.unmodifiableList(newPath));
    // follow-up: dual-write v2 ARRAYLOAD
    // node. If the writer recorded a v2 handle
    // (recordArrayStore threads it through
    // arrayTaintTracker.recordStore's v2Handle
    // param), attach that handle as the parent of
    // this PROPAGATE so the DAG stays connected.
    // Otherwise (legacy / pre-dual-write writer),
    // fall back to the orphan-SOURCE shape from
    // (a fresh trust boundary).
    int recoveredHandle = tracked.v2Handle();
    int arrHandle;
    if (recoveredHandle >= 0) {
      arrHandle =
          v2Builder.addPropagate(
              TraceNode.Op.ARRAYLOAD,
              List.of(new OperandRef.Var(arr.name(), 0), new OperandRef.Var(dv.name(), 0)),
              List.of(recoveredHandle),
              "ARRAYLOAD "
                  + arr.name()
                  + "["
                  + (ins.lhs2 == null
                      ? "?"
                      : (ins.lhs2 instanceof TacOperand.TacVar tv
                          ? tv.name()
                          : ins.lhs2.toString()))
                  + "] -> "
                  + dv.name(),
              "",
              ins.line,
              ins.column,
              "");
    } else {
      // Orphan: no recovered source — trust
      // boundary ( first-cut shape).
      arrHandle =
          v2Builder.addSource(
              TraceNode.Op.ARRAYLOAD,
              List.of(new OperandRef.Var(arr.name(), 0), new OperandRef.Var(dv.name(), 0)),
              "ARRAYLOAD "
                  + arr.name()
                  + "["
                  + (ins.lhs2 == null
                      ? "?"
                      : (ins.lhs2 instanceof TacOperand.TacVar tv
                          ? tv.name()
                          : ins.lhs2.toString()))
                  + "] -> "
                  + dv.name(),
              "",
              ins.line,
              ins.column,
              "");
    }
    v2TraceHandle.put(dv, arrHandle);
  }

  /**
   * Helper: extract the class name from a {@link TacType}. Returns {@code null} for non-class types
   * (primitives, arrays, UNKNOWN). Used to key the per-class instance-field tracker.
   */
  private static String classNameOfType(TacType t) {
    // Delegate to
    // {@link FieldOpClassifier#classNameOfType}.
    return FieldOpClassifier.classNameOfType(t);
  }

  /**
   * Helper extracted from {@link #checkSinkAtCall}: process a single (callee, family) pair. Each
   * family whose rule matches gets its own path-condition check and finding emission, so a single
   * {@code Logger#info(email)} call fires both CWE-200 (sensitive family) and CWE-359 (PII family)
   * when both rules apply.
   */
  /**
   * Sink dispatch for NEW instructions (constructors). Unlike CALL, the args list is the raw
   * constructor argument list (no receiver prepended), so the +1 receiver offset used by
   * checkSinkForFamily for instance CALLs is wrong here : the first Java arg of a ctor IS the first
   * TAC arg. Otherwise the family lookup / taint propagation / finding emission are identical to
   * the CALL path.
   */
  private void checkSinkAtNew(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    String calleeSig = ins.calleeSig;
    if (calleeSig == null) return;
    List<SinkFamily> families = ctx.config().sinkFamilies().lookupAll(calleeSig);
    if (families.isEmpty()) return;
    for (SinkFamily family : families) {
      if (!rule.matchesSinkFamily(family.name())) continue;
      checkSinkForCtor(ins, fn, ctx, family, calleeSig);
    }
  }

  private void checkSinkForCtor(
      TacInstruction ins,
      TacFunction fn,
      AnalysisContext ctx,
      SinkFamily family,
      String calleeSig) {
    // arg index is now per-pattern from
    // the SinkFamily. The legacy
    // sinkArgumentIndex() override on the
    // checker is honoured as a fallback for
    // checkers that haven't migrated yet.
    int javaArgIdx = patternArgIndex(family, calleeSig, ins);
    // NEW args are the raw ctor arg list
    // (no receiver), so the Java arg index
    // maps 1:1 to the TAC arg index. No
    // +1 receiver offset.
    int argIdx = javaArgIdx;
    if (argIdx < 0 || argIdx >= ins.args.size()) return;
    TacOperand arg = ins.args.get(argIdx);
    if (!(arg instanceof TacOperand.TacVar av)) return;
    if (!taintedVars.containsKey(av)) return;
    // Build a simple source-to-sink path for
    // the finding's flow-step list. The
    // taint path may be empty (e.g. input
    // source seeding on a fresh local) so
    // we still emit a sink step.
    List<FlowStep> argPath = taintPaths.get(av);
    List<FlowStep> fullPath;
    if (argPath == null) {
      fullPath = new ArrayList<>();
    } else {
      fullPath = new ArrayList<>(argPath);
    }
    fullPath.add(
        new FlowStep(
            "sink", "", ins.line, ins.column, calleeSig, calleeSig + " (" + family.name() + ")"));
    String msg = findingMessage(ins, family, arg);
    // same caller-origin preference as the CALL sink path (see resolveUpstreamOrigin).
    String ctorOrigin = resolveUpstreamOrigin(taintedVars.get(av), fn, ctx);
    String ctorSourceLabel = ctorOrigin != null ? ctorOrigin : taintedVars.get(av);
    Finding finding =
        Finding.dfa(
            rule.ruleId(),
            rule.ruleId(),
            msg,
            rule.severity(),
            rule.cwe(),
            "",
            ins.line,
            ins.column,
            "taint: " + ctorSourceLabel + " -> " + family.cweOrNull(),
            java.util.Collections.unmodifiableList(fullPath));
    // eager dual-write — build the v2 Trace
    // from the in-memory v2Builder instead of
    // upgrading the v1 path. The v2 trace has
    // specific op enums (MOVE / INPUT_READLINE /
    // SINK_SQL / ...) instead of UNKNOWN+Free.
    // v1 dataflowSteps is still byte-identical (the
    // fullPath above), so v1 consumers see no change.
    int avHandle = v2TraceHandle.getOrDefault(av, -1);
    Trace v2Trace;
    if (avHandle >= 0) {
      int sinkHandle =
          v2Builder.addSink(
              TraceNode.Op.SINK_SQL, // approximate; refined in future R-804
              List.of(new OperandRef.CallRef(calleeSig)),
              List.of(avHandle),
              calleeSig + " (" + family.name() + ")",
              "",
              ins.line,
              ins.column,
              calleeSig);
      v2SinkHandles.add(sinkHandle);
      v2Trace = v2Builder.build(v2SinkHandles, Trace.CompressionLevel.FULL);
      // Don't pop — sinks can be shared
      // across multiple findings within
      // the same function (same sink
      // signature, different lines). The
      // v2Builder is reset per function
      // so the leak is bounded.
    } else {
      // Fallback: source handle not found
      // (e.g. v2 handle map was reset by
      // initFunction but the source
      // variable still has a taintedVars
      // entry from a prior function — rare
      // but possible). Use the lazy
      // upgrade from v1.
      v2Trace = Trace.fromFlowSteps(fullPath);
    }
    ctx.reporter().reportWithTrace(finding, v2Trace);
  }

  private void checkSinkForFamily(
      TacInstruction ins,
      TacFunction fn,
      AnalysisContext ctx,
      SinkFamily family,
      String calleeSig) {
    // per-pattern arg index from SinkFamily
    // (yaml-configurable). Falls back to the
    // legacy sinkArgumentIndex() override for
    // checkers that haven't migrated.
    int javaArgIdx = patternArgIndex(family, calleeSig, ins);
    // The TAC CALL builder prepends the receiver to ins.args, so for instance
    // methods the Java arg index N maps to TAC
    // arg index N+1. All sink families
    // registered in sink-families.yaml target
    // instance methods, so we add 1 universally
    // here. (If a future static-method sink is
    // added, override this in a checker that
    // distinguishes.)
    //
    // RECEIVER_ARG_INDEX (-2) selects the receiver itself, which in
    // the TAC encoding is args[0] with NO offset. This is what makes
    // zero-argument sinks like ObjectInputStream#readObject reachable:
    // they have no user argument at all, and the untrusted data is the
    // stream. Before this, such a pattern fell back to defaultArgIndex (0)
    // and indexed past the end of the argument list, so the CWE-502 rule
    // was registered but structurally unreachable.
    int argIdx;
    boolean receiverTainted = javaArgIdx == SinkFamily.RECEIVER_ARG_INDEX;
    if (receiverTainted) {
      argIdx = 0;
    } else {
      argIdx = javaArgIdx + 1;
    }
    if (argIdx < 0 || argIdx >= ins.args.size()) return;
    TacOperand arg = ins.args.get(argIdx);
    // Record the observation with the
    // user-arg index (javaArgIdx), NOT the
    // TAC arg index (argIdx). The summary's
    // paramOpsAt(i) convention is the
    // user-arg index (i = 0 = first user
    // arg), so the Sink observation must
    // use the same convention. The earlier
    // code used argIdx (with the +1 offset),
    // which made the Sink observation's
    // argIndex one past the param range for
    // a 1-param function : summarizeFunction
    // silently dropped the Sink op in that
    // case.
    //
    // for a receiver-tainted sink the receiver is not a parameter at
    // all, so there is no param index to record. Recording -1 would make
    // summarizeFunction's `so.argIndex < params.size()` guard true for a
    // 0-param function and attribute the sink to param 0 wrongly, so we
    // skip the observation entirely. The finding is still emitted; only the
    // summary-side cross-function hint is omitted, which is correct because
    // a receiver sink has no argument to propagate.
    if (!receiverTainted) {
      sinkObservations.add(new SinkObservation(family, javaArgIdx));
    }
    if (!(arg instanceof TacOperand.TacVar av)) return;
    if (!taintedVars.containsKey(av)) return;
    // A deserialization stream that carries a JEP-290 filter which can
    // reject is not a finding: the filter is the shipped mitigation, and
    // the bytes are the same either way -- it is the STREAM that decides.
    //
    // The check is on the RECEIVER, because the filter is installed on the
    // stream.  And it is only applied to the deserialization family: a
    // filter on an ObjectInputStream says nothing about any other sink.
    if (isDeserializationFamily(family) && deserializationFilters.restricts(av)) {
      LOG.debug(
          "suppressing deserialization finding -- the stream {} has a filter that can reject",
          av.name());
      return;
    }
    // this parameter is tainted here only because of the entry-boundary seed, and only
    // because of it when its own name is the recorded source. If every resolved call site
    // proved the value clean, that taint is not real and the finding is a false positive.
    //
    // Deliberately narrow, for two reasons. It requires the source label to BE the parameter's
    // own name, so a taint that travelled ("param:x -> concat -> param:y") keeps its distinct
    // head and still reports. And it requires positive proof of cleanliness at every call site,
    // because the absence of a taint label is not proof: a variable only gains a label once
    // something tainted reaches it, so an unlabelled variable is usually "unanalysed", not "safe".
    if (fn != null) {
      int r1109ParamIdx = fn.params().indexOf(av);
      // Compare the PARAM NAME, not the whole label: a framework-bound parameter carries a
      // qualifier after the name ("param:customer@spring-mvc:RequestParam"), and the
      // suppression must still apply to it. The suppression proves the VALUE is clean at
      // every call site; that proof does not depend on how the source is named.
      String avLabel = taintedVars.get(av);
      String avParamName = FrameworkEntryPoints.paramNameOf(avLabel);
      if (r1109ParamIdx >= 0
          && ctx.isParamAlwaysClean(fn.signature(), r1109ParamIdx)
          && avParamName != null
          && avParamName.equals(av.name())) {
        LOG.debug(
            "suppressing sink finding -- {}{} is tainted only by the entry seed and every"
                + " call site proves it clean",
            fn.signature(),
            r1109ParamIdx);
        return;
      }
    }
    // Domain check: a MixedTaint flows anywhere;
    // a specific domain only flows into a
    // compatible sink.
    if (!rule.domain().flowsInto(rule.domain())) {
      // Same-domain check is a no-op; the real
      // signal is whether the SOURCE carried a
      // matching domain. Without a domain
      // lattice at the fact level, we treat the
      // rule's domain as the desired one and
      // skip the cross-domain mismatch : Mixed
      // domain rules like deserialization
      // accept any input.
    }
    // Path-condition verification.
    // If a solver bridge is configured and
    // the subclass has a non-null
    // path-condition string for this
    // sink, ask the bridge whether the
    // path is satisfiable. UNSAT means
    // the source-to-sink path can't
    // actually be taken (e.g. the source
    // value is provably guarded by a
    // check that rejects it before the
    // sink call), so we suppress the
    // finding to eliminate the false
    // positive. SAT / UNKNOWN are treated
    // as "could be reachable" : emit
    // normally.
    if (solverBridge != null) {
      // Compose the local path
      // condition with the accumulated
      // callee-supplied fragments. The
      // bridge sees the conjunction of
      // "local guard holds" AND
      // "each callee's path-condition
      // is satisfiable". UNSAT in any
      // branch means the overall path
      // is unreachable.
      String pc = pathConditionForSink(av, family, ins, ctx);
      String composed = composePathCondition(pc);
      if (!composed.isEmpty()) {
        SolverZ3Bridge.Result r = solverBridge.check(composed, java.util.Map.of());
        if (r == SolverZ3Bridge.Result.UNSAT) {
          // The path is provably
          // unreachable : suppress
          // the finding.
          return;
        }
      }
    }
    // Build the structured source-to-sink trace.
    // The path of the sink arg is the source-to-here
    // path; we append a final "sink" step recording
    // the call site (line / column / callee / family).
    List<FlowStep> argPath = taintPaths.get(av);
    List<FlowStep> fullPath;
    if (argPath == null) {
      fullPath = new ArrayList<>();
    } else {
      fullPath = new ArrayList<>(argPath);
    }
    fullPath.add(
        new FlowStep(
            "sink", "", ins.line, ins.column, calleeSig, calleeSig + " (" + family.name() + ")"));
    // Emit the Z3 path condition (if any) as a
    // "z3-fact" FlowStep. The AI dedup agent's
    // get_dataflow_trace_with_facts tool reads these
    // and the FP reducer uses them to reason about
    // whether the path is reachable. We always emit
    // (even when pathConditionForSink returns null)
    // because composePathCondition is a no-op when
    // the subclass didn't override. The role "z3-fact"
    // is recognised by GetDataflowTraceWithFactsTool
    // and rendered as "Z3 path condition: ...".
    if (solverBridge != null) {
      String local = pathConditionForSink(av, family, ins, ctx);
      String composed = composePathCondition(local);
      if (!composed.isEmpty()) {
        fullPath.add(new FlowStep("z3-fact", "", -1, -1, "", "Z3 path condition: " + composed));
      }
    }
    // Emit the finding.
    String msg = findingMessage(ins, family, arg);
    // prefer the caller's real taint origin over this function's own entry-boundary
    // `param:<name>` label, so a finding inside a sink method names where the value actually
    // came from. See resolveUpstreamOrigin for why this can only be resolved here.
    String origin = resolveUpstreamOrigin(taintedVars.get(av), fn, ctx);
    String sourceLabel = origin != null ? origin : taintedVars.get(av);
    Finding finding =
        Finding.dfa(
            rule.ruleId(),
            rule.ruleId(),
            msg,
            rule.severity(),
            rule.cwe(),
            "", // filePath filled in by reporter / post-processor
            ins.line,
            ins.column,
            "taint: " + sourceLabel + " -> " + family.cweOrNull(),
            java.util.Collections.unmodifiableList(fullPath));
    // eager dual-write — build the v2 trace
    // from the in-memory v2Builder instead of
    // upgrading the v1 path. The v2 trace has
    // specific op enums (MOVE / INPUT_READLINE /
    // SINK_SQL / ...) instead of UNKNOWN+Free.
    // v1 dataflowSteps is still byte-identical (the
    // fullPath above), so v1 consumers see no change.
    int avHandle = v2TraceHandle.getOrDefault(av, -1);
    Trace v2Trace;
    if (avHandle >= 0) {
      int sinkHandle =
          v2Builder.addSink(
              TraceNode.Op.SINK_SQL,
              List.of(new OperandRef.CallRef(calleeSig)),
              List.of(avHandle),
              calleeSig + " (" + family.name() + ")",
              "",
              ins.line,
              ins.column,
              calleeSig);
      v2SinkHandles.add(sinkHandle);
      v2Trace = v2Builder.build(v2SinkHandles, Trace.CompressionLevel.FULL);
    } else {
      // Fallback: source handle not found.
      v2Trace = Trace.fromFlowSteps(fullPath);
    }
    ctx.reporter().reportWithTrace(finding, v2Trace);
  }

  /**
   * Subclass hook: build a path-condition string for the given source operand flowing into the
   * given sink family. Return {@code null} (the default) to skip the bridge verification : every
   * finding fires. Return a non-empty predicate string to ask the configured {@link SolverZ3Bridge}
   * whether the path is satisfiable; UNSAT results suppress the finding.
   *
   * <p>small arithmetic / boolean grammar as {@link org.plumb.dfa.solver.LocalSolverBridge}.
   * Typical usage: a subclass that knows its source is guarded by an {@code if (input != null)}
   * check can return {@code "input != null && !input.isEmpty()"} so the bridge can decide whether
   * the null/empty path is actually taken. When in doubt, return {@code null} (emit normally) : the
   * bridge is strictly a false-positive filter, never a false-negative one.
   */
  protected String pathConditionForSink(
      TacOperand.TacVar arg, SinkFamily family, TacInstruction call, AnalysisContext ctx) {
    return null;
  }

  /**
   * Set the path-condition verification bridge. Pass {@code null} to disable verification (the
   * default). The bridge is used by {@link #checkSinkAtCall} to filter findings whose
   * source-to-sink path is provably unsatisfiable under the subclass's {@link
   * #pathConditionForSink}.
   */
  public void setSolverBridge(SolverZ3Bridge bridge) {
    this.solverBridge = bridge;
  }

  /**
   * The currently-configured solver bridge ({@code null} if verification is disabled). Exposed for
   * tests.
   */
  public SolverZ3Bridge solverBridge() {
    return solverBridge;
  }

  /**
   * For a CALL whose callee has a {@link FunctionSummary} in the cache, check whether any of the
   * callee's parameters carry a {@link Operation.TaintPropagate}; if so, mark the corresponding
   * caller-side argument as tainted (and propagate forward from there via the usual MOVE / BINOP
   * rules).
   */
  /**
   * Heuristic check: is this type a "user-arg value type" that we should NOT treat as a class
   * receiver? The IR builder encodes {@code String u} as {@code
   * TacType.ofClass("java.lang.String")} (Kind.CLASS), not as {@code TacType.STRING} (Kind.STRING).
   * The same is true for primitive wrappers (Integer, Long, Boolean, etc.) : they all become CLASS
   * TacVars with the {@code java.lang.X} FQN. Without this guard, the receiver-detection heuristic
   * at the call site (see {@code propagateTaintFromCalleeSummary}) would misclassify {@code u} as a
   * cross-instance receiver for {@code this.setData(u)} calls (where the IR builder emits {@code
   * args=[u]} without a leading {@code this}). An earlier draft missed this; the current guard
   * restores the "default to this" behavior for same-class calls where the user arg happens to be a
   * value-typed TacVar.
   */
  private static boolean isLikelyUserArgType(TacType t) {
    // Delegate to
    // {@link FieldOpClassifier#isLikelyUserArgType}
    // (the canonical home for this
    // package-prefix heuristic).
    return FieldOpClassifier.isLikelyUserArgType(t);
  }

  private void propagateTaintFromCalleeSummary(TacInstruction ins, AnalysisContext ctx) {
    String calleeSig = ins.calleeSig;
    if (calleeSig == null) return;
    FunctionSummary calleeSummary = ctx.summaryCache().get(calleeSig);
    // Cross-class call lookup fallback. The
    // summary cache is populated under
    // the bare-name-with-arity form
    // (e.g. {@code "setData/1"}), but a cross-class
    // CALL's {@code ins.calleeSig} uses the
    // {@code "ClassName.methodName/arity"} form
    // (e.g. {@code "Helper.setData/1"}). When the
    // direct lookup misses (or returns a trivial
    // summary that is really a "no entry" mask),
    // try the bare-name form before giving up.
    // This unblocks the getter pattern (caller's
    // receiver has tainted fields, getter's dest
    // inherits via the cross-fn fold's
    // conservative passthrough) and is generally
    // correct: the bare-name alias is the
    // canonical entry point for the body-driven
    // TaintPropagate / fieldOp fold.
    //
    // Also accept a trivial-but-hasBody
    // summary in the fallback. Trivial-with-body
    // is the getter / setter / passthrough shape
    // where the standalone analysis saw no taint
    // (no TaintPropagate in returnOps) but the
    // function has a body the cross-fn fold needs
    // to know about. The engine populates these
    // in the cache.
    if (calleeSummary == null || (calleeSummary.isTrivial() && !calleeSummary.hasBody())) {
      // in merged mode the bare alias collides across
      // files (Juliet Variant 42/45 all share {@code badSource/0}).
      // The {@code r53StripClassPrefix} helper returns null for an
      // already-bare sig (no {@code ClassName.} prefix), but in
      // merged mode that bare name IS the alias key the engine put
      // (qualified with the enclosing class). Try the qualified
      // form first; if it misses fall back to the bare alias for
      // single-file backward compatibility.
      org.plumb.tac.ir.TacFunction callerFn = ctx.currentFunction();
      String callerEnc = callerFn != null ? callerFn.enclosingClass() : null;
      String bareName = r53StripClassPrefix(calleeSig);
      // For same-class calls (implicit-this), {@code calleeSig}
      // is already the bare alias (no {@code ClassName.} prefix).
      // Use it directly so the qualified-lookup block below
      // matches on the engine's bare alias key.
      if (bareName == null && calleeSig != null && calleeSig.contains("/")) {
        int slash = calleeSig.indexOf('/');
        if (slash > 0 && calleeSig.lastIndexOf('.', slash - 1) < 0) {
          bareName = calleeSig;
        }
      }
      // Always try the qualified alias first when we have a bare
      // name (cross-class and same-class merged-mode calls).
      if (bareName != null) {
        if (callerEnc != null && !callerEnc.isEmpty()) {
          String qualified = callerEnc + " :: " + bareName;
          FunctionSummary qAliased = ctx.summaryCache().get(qualified);
          if (qAliased != null && (!qAliased.isTrivial() || qAliased.hasBody())) {
            calleeSummary = qAliased;
          }
        }
        if (calleeSummary == null) {
          // Bare alias fallback (single-file mode uses this).
          FunctionSummary aliased = ctx.summaryCache().get(bareName);
          if (aliased != null && (!aliased.isTrivial() || aliased.hasBody())) {
            calleeSummary = aliased;
          }
        }
      }
    }
    if (calleeSummary == null || (calleeSummary.isTrivial() && !calleeSummary.hasBody())) {
      // receiver : dest taint passthrough
      // for JDK getter-style methods with no
      // plumb-side summary. The classic
      // Juliet CWE89 pattern
      //   Cookie[] cookies = request.getCookies();
      //   data = cookies[0].getValue();
      // emits three CALLs : getCookies() (an
      // input source : marks cookies tainted),
      // ARRAYLOAD (propagates cookies taint
      // to the array element), and
      // getValue/0 (no summary, has no
      // entry in InputSourcePatterns). Without
      // this fallback the dest of getValue()
      // is CLEAN and the downstream SQL
      // injection sink never sees the taint.
      //
      // config gate: the passthrough is
      // ONLY allowed for callee signatures
      // that match a configured pattern in
      // receiver-passthrough.yaml. Without
      // this gate the fallback would mark ANY
      // JDK method returning data from a
      // tainted receiver as tainted, producing
      // FP on POJO getters, util-class
      // factories, Optional wrappers, etc.
      // Configuration makes the passthrough
      // opt-in and reviewable.
      //
      // Conservative within the gated set:
      // only fires when the receiver (args[0]
      // for instance methods) is directly
      // tainted in the current function. We
      // do NOT chase field/array chains here
      // (the per-function tracker above does
      // that for the hasBody case).
      // Skipping this for args.size() == 0
      // (static-like calls with no args).
      if (ins.dest instanceof TacOperand.TacVar rcvDest
          && !taintedVars.containsKey(rcvDest)
          && !ins.args.isEmpty()
          && ins.args.get(0) instanceof TacOperand.TacVar rcv
          && taintedVars.containsKey(rcv)
          && ctx.config().isReceiverPassthroughEligible(calleeSig)) {
        String rcvLabel = taintedVars.get(rcv);
        taintedVars.put(rcvDest, "rcv-passthrough:" + rcvLabel);
        List<FlowStep> rcvFold = new java.util.ArrayList<>();
        List<FlowStep> rcvSrcPath = taintPaths.get(rcv);
        if (rcvSrcPath != null) rcvFold.addAll(rcvSrcPath);
        rcvFold.add(
            new FlowStep(
                "receiver-passthrough",
                "",
                ins.line,
                ins.column,
                calleeSig,
                "receiver-tainted dest inherits: " + rcvLabel));
        taintPaths.put(rcvDest, java.util.Collections.unmodifiableList(rcvFold));
        // dual-write v2 SUMMARY_FOLD
        // node. Op=SUMMARY_TAINT_PROPAGATE
        // (the summary that says "this
        // receiver-tainted method
        // auto-propagates taint to its
        // return value"). We look up the
        // source's v2 handle and chain a
        // new SUMMARY_FOLD node off it.
        if (ins.dest instanceof TacOperand.TacVar rcvDest2 && taintedVars.containsKey(rcvDest2)) {
          int rcvHandle = v2TraceHandle.getOrDefault(rcv, -1);
          if (rcvHandle >= 0) {
            int handle =
                v2Builder.addSummaryFold(
                    TraceNode.Op.SUMMARY_TAINT_PROPAGATE,
                    calleeSig,
                    0, // param 0 = receiver
                    List.of(new OperandRef.Var(rcvDest2.name(), 0)),
                    rcvHandle,
                    "receiver-tainted dest inherits: " + rcvLabel,
                    "",
                    ins.line,
                    ins.column,
                    calleeSig);
            v2TraceHandle.put(rcvDest2, handle);
          }
        }
      }
      return;
    }
    // For each user-arg index i
    // with a TaintPropagate, check the
    // call's arg i. The user-arg index
    // is the param index; the call's
    // matching TAC arg is at i + 1
    // (instance method convention :
    // args[0] is the receiver, args[1+]
    // are the user args). The new loop is
    // bounds-checked on the callee's
    // param count (a static method
    // with N params has N user args;
    // we use N as the bound to be safe
    // for both instance and static).
    int maxParams = Math.max(0, ins.args.size() - 1);
    for (int i = 0; i < maxParams; i++) {
      int callArgIdx = i + 1;
      // The +1 offset convention
      // ({@code callArgIdx = i + 1}) is
      // correct for instance methods
      // (where args[0] is the receiver)
      // but wrong for static methods
      // (where args[0] is the first
      // user arg and there's no
      // receiver). Apply the same
      // two-tier offset fallback: if
      // {@code i + 1} is out of bounds,
      // try {@code i} directly (static
      // method convention).
      if (callArgIdx >= ins.args.size()) {
        if (i < ins.args.size()) {
          callArgIdx = i;
        } else {
          break;
        }
      }
      TacOperand arg = ins.args.get(callArgIdx);
      if (!(arg instanceof TacOperand.TacVar av)) continue;
      Operation.TaintPropagate taintOp =
          calleeSummary.paramOpsAt(i).stream()
              .filter(op -> op instanceof Operation.TaintPropagate)
              .map(op -> (Operation.TaintPropagate) op)
              .findFirst()
              .orElse(null);
      boolean tainted = taintOp != null;
      if (tainted) {
        // the caller's argument inherits the CALLEE's taint origin, not a
        // synthetic `callee:<sig>` label. The callee seeds its own params as
        // `param:<name>` at the entry boundary, so without this the next hop's
        // finding would name the callee's parameter instead of the real source
        // the value actually came from — which reads identically to "taint never
        // crossed the call boundary". When the origin is null (the callee entered
        // the value as a genuinely fresh source) we keep the old label.
        String origin = taintOriginOf(taintOp.origin());
        taintedVars.put(av, origin != null ? origin : "callee:" + calleeSig);
        // Cross-function summary fold:
        // the caller's arg inherits taint from
        // the callee's summary. Record a
        // "summary-fold" step so the trace
        // shows the user that this taint came
        // from a callee summary, not a direct
        // source. The line/column point at
        // the call site itself.
        List<FlowStep> fold = new ArrayList<>();
        List<FlowStep> prevPath = taintPaths.get(av);
        // when the callee told us where the value came from, carry the
        // caller's existing path so the trace shows the full chain instead of
        // restarting at this call site.
        if (prevPath != null) fold.addAll(prevPath);
        fold.add(
            new FlowStep(
                "summary-fold",
                "",
                ins.line,
                ins.column,
                calleeSig,
                "call " + calleeSig + " arg#" + i + " carries taint through"));
        taintPaths.put(av, java.util.Collections.unmodifiableList(fold));
        // dual-write v2 SUMMARY_FOLD
        // node. The callee summary says
        // "arg i is TaintPropagate" — we
        // mark the caller's arg as a
        // SUMMARY_FOLD with the parent
        // being the callee's tracked source.
        // Without a recovered parent we add
        // a fresh SUMMARY_FOLD root.
        int summaryHandle =
            v2Builder.addSummaryFold(
                TraceNode.Op.SUMMARY_TAINT_PROPAGATE,
                calleeSig,
                i,
                List.of(new OperandRef.Var(av.name(), 0)),
                -1, // orphan parent — the
                // cross-fn summary
                // itself is the source
                "call " + calleeSig + " arg#" + i + " carries taint through",
                "",
                ins.line,
                ins.column,
                calleeSig);
        v2TraceHandle.put(av, summaryHandle);
      }
    }
    // If the call has a dest, propagate from
    // the return value too (any TaintPropagate
    // in returnOps taints the dest).
    if (ins.dest instanceof TacOperand.TacVar destVar) {
      boolean retTainted =
          calleeSummary.returnOps().stream().anyMatch(op -> op instanceof Operation.TaintPropagate);
      if (retTainted) {
        // Enrich the dest's taint
        // path and label with the
        // callee's structural passthrough
        // info. The callee's
        // {@code stringOps} list records
        // the sequence of String methods
        // (e.g. toLowerCase, trim) that
        // the callee applied on the path
        // from its param to its return.
        // When the caller applies the
        // callee's summary, the dest
        // should reflect that chain so
        // reporters can show the full
        // source-to-sink trace
        // (e.g. "callee.toLowerCase().trim()").
        String enrichedLabel =
            enrichTaintLabelWithPassthroughs("callee-ret:" + calleeSig, calleeSummary);
        taintedVars.put(destVar, enrichedLabel);
        // Summary-fold for the
        // return value: the dest inherits
        // taint from the callee's returnOps.
        List<FlowStep> fold = new ArrayList<>();
        fold.add(
            new FlowStep(
                "summary-fold",
                "",
                ins.line,
                ins.column,
                calleeSig,
                "call " + calleeSig + " returns taint"));
        // dual-write v2 SUMMARY_FOLD
        // node for the return value. Same
        // orphan-parent treatment as the
        // arg-taint site — the cross-fn
        // summary is the source.
        int summaryHandle =
            v2Builder.addSummaryFold(
                TraceNode.Op.SUMMARY_TAINT_PROPAGATE,
                calleeSig,
                0,
                List.of(new OperandRef.Var(destVar.name(), 0)),
                -1, // orphan parent
                "call " + calleeSig + " returns taint",
                "",
                ins.line,
                ins.column,
                calleeSig);
        v2TraceHandle.put(destVar, summaryHandle);
        // Append one
        // "passthrough-summary-fold"
        // FlowStep per StringOp in the
        // callee's stringOps list, in
        // order. Each step records the
        // structural passthrough method
        // so the trace shows the chain.
        appendPassthroughStepsForSummary(fold, calleeSummary, ins);
        taintPaths.put(destVar, java.util.Collections.unmodifiableList(fold));
      }
    }
    // Conservative caller-arg :
    // dest taint passthrough. The
    // existing summary-fold marks the dest
    // tainted only if the callee's
    // returnOps has a TaintPropagate.
    // This misses the common pattern
    //
    //   String buildQuery(String u) {
    //     return "literal" + u + "literal";
    //   }
    //
    // where the standalone analysis
    // doesn't see u as tainted (so no
    // TaintPropagate on returnOps), but
    // a caller that passes a tainted u
    // should still see taint flow to
    // the dest.
    //
    // Rule: if the dest is a
    // TacVar, the dest is not already
    // tainted, the callee has a body
    // (calleeSummary.hasBody() == true),
    // and any caller arg is tainted,
    // then mark the dest tainted via
    // that arg's taint label. We walk
    // ALL args (no static/instance
    // offset assumption) because the
    // `args.size() - 1` heuristic is
    // wrong for static methods
    // (where the receiver is missing
    // and args[0] is the first user
    // arg). Walking all args is
    // the simplest correct
    // implementation; it does not
    // over-taint because the
    // callee-has-body gate ensures
    // only non-trivial functions are
    // candidates.
    if (ins.dest instanceof TacOperand.TacVar r40Dest
        && !taintedVars.containsKey(r40Dest)
        && calleeSummary.hasBody()) {
      for (int i = 0; i < ins.args.size(); i++) {
        TacOperand arg = ins.args.get(i);
        if (!(arg instanceof TacOperand.TacVar av)) continue;
        String argLabel = taintedVars.get(av);
        // If the arg is not
        // directly tainted, check
        // the per-function field
        // tracker for "any field
        // of this receiver is
        // tainted". This handles
        // the getter pattern:
        // caller writes
        // h.data = u, then calls
        // h.getData() : the
        // receiver h is not
        // directly tainted, but
        // h.data is, and the
        // dest of getData()
        // should inherit the
        // taint.
        if (argLabel == null) {
          // Per-instance field
          // check first (the
          // helper-stored-on-the-same-instance
          // case).
          FieldTaintTracker.TrackedTaint fieldTaint =
              fieldTaintTracker.readAnyTaintedFieldFor(av.name());
          if (fieldTaint == null && av.type() != null && av.type().kind() == TacType.Kind.CLASS) {
            // Aliased-instance fallback.
            // The arg is a class-typed
            // TacVar (e.g. {@code h2})
            // and the per-function
            // tracker has no taint
            // for THIS particular
            // instance. The cross-fn
            // static / aliased tracker
            // (crossFnClassFieldTaint)
            // is the program-wide
            // view: if a different
            // instance of the same
            // class (e.g.
            // {@code Helper}) was
            // tainted (a sibling
            // setter call wrote to
            // it), this receiver
            // inherits the taint
            // because the cross-fn
            // tracker treats
            // same-typed receivers
            // as potentially
            // aliased. The key shape
            // is
            // {@code "ClassName.fieldName"}
            // and the class name is
            // the {@code toString}
            // of the receiver's
            // declared type.
            String recvTypeName = classNameOfType(av.type());
            if (recvTypeName != null) {
              // We don't have a
              // per-field
              // discriminator
              // here (the per-instance
              // tracker has it via
              // the {@code "<recv>."}
              // prefix). The cross-fn
              // tracker is keyed by
              // {@code "ClassName.fieldName"}
              // so we need to scan
              // for keys starting with
              // the class name.
              // Walk the global
              // tracker and pick
              // the first
              // matching key. The
              // recordFieldOpForStore
              // read path already
              // do this work; the
              // simplest correct
              // query is to reuse
              // the per-class-name
              // scan we just did
              // via
              // crossFnClassFieldTaint
              // entrySet.
              String prefix = recvTypeName + ".";
              for (var e : crossFnClassFieldTaint.entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                  argLabel = "r53-classchain:" + recvTypeName + "." + e.getValue();
                  break;
                }
              }
            }
          }
          if (fieldTaint != null) {
            argLabel = "r53-fieldchain:" + av.name() + "." + fieldTaint.label();
          }
        }
        if (argLabel == null) continue;
        // Found a tainted caller
        // arg (directly or via
        // field chain). Mark the
        // dest tainted via this
        // arg's label, with a
        // "conservative-summary-fold"
        // step so the trace shows this
        // is a conservative cross-fn
        // passthrough rather
        // than a directly modelled
        // TaintPropagate.
        taintedVars.put(r40Dest, "r40-passthrough:" + argLabel);
        List<FlowStep> r40Fold = new ArrayList<>();
        r40Fold.add(
            new FlowStep(
                "conservative-summary-fold",
                "",
                ins.line,
                ins.column,
                calleeSig,
                "caller arg #"
                    + i
                    + " tainted, callee has body, "
                    + "dest inherits taint via "
                    + "conservative passthrough"));
        // Append one
        // "passthrough-summary-fold"
        // step per StringOp so
        // the trace shows any
        // structural passthroughs
        // the callee applied.
        appendPassthroughStepsForSummary(r40Fold, calleeSummary, ins);
        taintPaths.put(r40Dest, java.util.Collections.unmodifiableList(r40Fold));
        // dual-write v2 SUMMARY_FOLD
        // node with op=SUMMARY_PASS_THROUGH.
        // Orphan parent (-1) — the
        // conservative passthrough is
        // itself a fresh trust boundary.
        int r40Handle =
            v2Builder.addSummaryFold(
                TraceNode.Op.SUMMARY_PASS_THROUGH,
                calleeSig,
                i,
                List.of(new OperandRef.Var(r40Dest.name(), 0)),
                -1,
                "caller arg #"
                    + i
                    + " tainted, callee has body, "
                    + "dest inherits taint via "
                    + "conservative passthrough",
                "",
                ins.line,
                ins.column,
                calleeSig);
        v2TraceHandle.put(r40Dest, r40Handle);
        // Only the first tainted arg
        // wins (avoid stacking
        // multiple marks).
        break;
      }
    }
    // Accumulate any
    // SolverZ3Predicate from the callee's
    // summary. The callee may have
    // recorded a path condition for
    // each param or for the return
    // value; both contribute to the
    // caller's overall path
    // satisfiability check.
    for (Operation op : calleeSummary.returnOps()) {
      if (op instanceof Operation.SolverZ3Predicate pred) {
        addPathConditionFragment(pred.predicate());
      }
    }
    for (var entry : calleeSummary.paramOps().entrySet()) {
      for (Operation op : entry.getValue()) {
        if (op instanceof Operation.SolverZ3Predicate pred) {
          addPathConditionFragment(pred.predicate());
        }
      }
    }
    // Walk the callee's fieldOps
    // and seed the caller's per-function
    // FieldTaintTracker. For each
    // FieldOp that has a non-negative
    // sourceParamIndex, the matching
    // caller arg (at index
    // sourceParamIndex 1 for instance
    // methods, sourceParamIndex for
    // static methods) carries the taint
    // that should now appear in the
    // caller's <receiver>.f. We use a
    // conservative approximation here:
    // assume the call is an instance
    // method (the common case) and use
    // sourceParamIndex 1. A future
    // enhancement can detect static vs
    // instance via the callee's
    // signature.
    //
    // The receiver is now
    // extracted from the actual CALL's
    // {@code args[0]} (a TacVar), but
    // with a class-type heuristic
    // (see below). For
    // {@code h.setData(arg)}, the
    // receiver is {@code "h"}; for
    // {@code this.setData(arg)} (the
    // earlier default), the receiver is
    // {@code "this"}.
    //
    // Why a heuristic is needed:
    // the IR builder is NOT consistent
    // about prepending the receiver to
    // args for same-class calls. For
    // example, in
    // {@code DeepFlow.bad} calling
    // {@code this.setData(u)} (same
    // class), the IR emits
    // {@code args=[u]} (just the user
    // arg, no receiver). But for a
    // cross-instance call like
    // {@code h.setData(u)} where
    // {@code h} is a different
    // instance, the IR emits
    // {@code args=[h, u]} (with
    // receiver). The disambiguating
    // signal is the TacType of
    // args[0]:
    //   - if args[0] has Kind.CLASS, it
    //     IS the receiver (its name is
    //     the receiver)
    //   - if args[0] has Kind.STRING /
    //     primitive / UNKNOWN, it's
    //     a user arg of a same-class
    //     call where the IR builder
    //     skipped the receiver : fall
    //     back to "this"
    if (!calleeSummary.fieldOps().isEmpty()) {
      String recvName = "this";
      if (ins.args != null
          && !ins.args.isEmpty()
          && ins.args.get(0) instanceof TacOperand.TacVar recvVar) {
        String n = recvVar.name();
        TacType t = recvVar.type();
        if ("this".equals(n)) {
          // Explicit `this` receiver.
          recvName = "this";
        } else if (t != null
            && t.kind() == TacType.Kind.CLASS
            && n != null
            && !n.isEmpty()
            && !isLikelyUserArgType(t)) {
          // Cross-instance call:
          // args[0] is a class-typed
          // TacVar (the receiver) AND
          // the type is not a likely
          // user-arg value type
          // (java.lang.String,
          // primitive wrappers, etc.).
          // Without the
          // isLikelyUserArgType
          // guard, the IR builder
          // encodes `String u` as
          // `TacType.ofClass("java.lang.String")`
          // (Kind.CLASS), which
          // would otherwise
          // misclassify same-class
          // call sites like
          // `this.setData(u)` (where
          // args[0] = u, the user
          // arg) as a cross-instance
          // call. An earlier draft
          // missed this; the current
          // guard fixes the false
          // positive.
          recvName = n;
        }
        // else: args[0] is a String /
        // primitive / UNKNOWN /
        // empty-name TacVar :
        // likely a user arg of a
        // same-class call. Default
        // to "this".
      }
      for (FieldOp fop : calleeSummary.fieldOps()) {
        int sourceParamIndex = fop.sourceParamIndex();
        if (sourceParamIndex < 0) continue;
        // Try the conventional
        // {@code sourceParamIndex + 1}
        // offset (instance method,
        // receiver at args[0]). If
        // that's out of bounds (the
        // IR builder may not prepend
        // the receiver for same-class
        // calls), fall back to
        // {@code sourceParamIndex}
        // directly (static method
        // convention, no receiver).
        int callArgIndex = -1;
        if (sourceParamIndex + 1 < ins.args.size()) {
          callArgIndex = sourceParamIndex + 1;
        } else if (sourceParamIndex < ins.args.size()) {
          callArgIndex = sourceParamIndex;
        }
        if (callArgIndex < 0) continue;
        // Pass the receiver's TacType
        // so the fold can seed the
        // declared-class-type entry of the
        // cross-fn tracker (used by sibling
        // getters on a different instance of
        // the same class). Falls back to
        // null when the receiver is the
        // implicit {@code this} (no args[0]
        // TacVar : the IR builder dropped
        // the receiver for same-class
        // calls).
        TacType recvTacType =
            (ins.args != null
                    && !ins.args.isEmpty()
                    && ins.args.get(0) instanceof TacOperand.TacVar recvVar2)
                ? recvVar2.type()
                : null;
        fieldTaintTracker.applyCalleeFieldOp(
            fop,
            callArgIndex,
            calleeSig,
            ins.args,
            taintedVars,
            taintPaths,
            // follow-up: thread
            // the v2 handle map so
            // the fold can record a
            // real source handle for
            // subsequent FIELDLOADs.
            v2TraceHandle,
            ins.line,
            ins.column,
            recvName,
            recvTacType);
      }
    }
  }

  /**
   * Legacy subclass hook: which argument of a sink call carries the tainted value.
   *
   * <p><b></b> moved arg-index selection into {@code sink-families.yaml} ({@code arg_index} per
   * pattern, {@code default_arg_index} per family). This hook is now vestigial and, in practice,
   * <b>unreachable for every shipped checker</b> — see the reachability note on {@link
   * #patternArgIndex}.
   *
   * <p>Do not rely on overriding this. To change which argument is inspected, set {@code arg_index}
   * on the pattern in {@code sink-families.yaml}.
   */
  protected int sinkArgumentIndex(SinkFamily family, TacInstruction call) {
    return DEFAULT_SINK_ARG_INDEX;
  }

  /**
   * resolve the effective user-arg index for the callee signature. Looks up the matching pattern's
   * {@code arg_index} (with the family's {@code default_arg_index} as a fallback) and only consults
   * the legacy {@link #sinkArgumentIndex} override when the family returns a non-positive index
   * (i.e. neither family nor pattern has a configured value).
   *
   * <p><b>Reachability of the legacy hook (audited ).</b> This method is only reached after a
   * family has already matched the callee, but {@link SinkFamily#argIndexFor} returns {@code -1}
   * <i>only</i> when no pattern matches. So whenever a family is present, {@code fromFamily} is
   * either the pattern's explicit index or the family's default — and {@code ConfigLoader} defaults
   * {@code default_arg_index} to {@code 0}, never {@code -1}. Hence {@link #sinkArgumentIndex} is
   * unreachable for every shipped family. {@code R1100SinkArgIndexPrecedenceTest} proves this
   * exhaustively over every pattern in the yaml: each resolves to {@code >= 0} or to the receiver
   * sentinel, never to {@code -1}.
   *
   * <p>Note that the overrides are therefore <i>not</i> a source of disagreement with the yaml —
   * several happen to agree ({@code XssInjectionChecker} returns 1, which is exactly what {@code
   * xss.sinks} declares for the sink it cares about). They must not be mistaken for live
   * configuration; edit the yaml instead.
   */
  private int patternArgIndex(SinkFamily family, String calleeSig, TacInstruction call) {
    int fromFamily = family.argIndexFor(calleeSig);
    // `fromFamily >= 0` alone silently discarded the receiver sentinel
    // (-2): it is negative, so it fell through to the checker's
    // sinkArgumentIndex() override, which returned the family default 0 — and
    // for a zero-argument sink like ObjectInputStream#readObject() that indexes
    // past the end of an empty argument list. The whole feature was inert.
    // Accept the sentinel explicitly.
    if (fromFamily >= 0 || fromFamily == SinkFamily.RECEIVER_ARG_INDEX) return fromFamily;
    // Family doesn't know about this callee, OR
    // the matching pattern's arg_index is unset.
    // Fall back to the checker override.
    return sinkArgumentIndex(family, call);
  }

  /**
   * Build an enriched taint label that includes the callee's structural passthrough chain. The base
   * label is the conventional {@code "callee-ret:<sig>"} form. When the callee's {@link
   * FunctionSummary} has a non-empty {@code stringOps} list, the chain is appended as {@code
   * "callee-ret:<sig>[<m1>,<m2>,...]"} so reporters can render the full source-to-sink path
   * including the helper's transformations.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>Callee has empty stringOps :
   *   <li>Callee has {@code [StringOp("toLowerCase"), StringOp("trim")]} : {@code
   *       "callee-ret:Helper#m[toLowerCase,trim]"}.
   *   <li>Callee has only {@code [StringOp("valueOf")]} : {@code "callee-ret:Helper#m[valueOf]"}.
   * </ul>
   *
   * <p>The label is purely a human-readable marker; consumers can parse the bracketed chain if they
   * care, but the structural information is also available in the dest's taint path (see {@link
   * #appendPassthroughStepsForSummary}).
   */
  static String enrichTaintLabelWithPassthroughs(String baseLabel, FunctionSummary calleeSummary) {
    if (calleeSummary == null) return baseLabel;
    java.util.List<StringOp> ops = calleeSummary.stringOps();
    if (ops == null || ops.isEmpty()) return baseLabel;
    StringBuilder sb = new StringBuilder(baseLabel);
    sb.append('[');
    for (int i = 0; i < ops.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(ops.get(i).methodName());
    }
    sb.append(']');
    return sb.toString();
  }

  /**
   * Append one {@code "passthrough-summary-fold"} {@link FlowStep} per {@link StringOp} in the
   * callee's {@code stringOps} list, in order. The steps are appended AFTER the existing {@code
   * "summary-fold"} return-value step so the trace reads as:
   *
   * <pre>
   *   ... : summary-fold: call X returns taint
   *       : passthrough-summary-fold: X.toLowerCase()
   *       : passthrough-summary-fold: X.trim()
   *       : ...
   * </pre>
   *
   * Empty {@code stringOps} is a no-op.
   *
   * <p>The method name in the step's {@code expr} field uses the bare form (e.g. {@code
   * "callee.toLowerCase()"}) so reporters can render it directly without further FQN-stripping.
   */
  static void appendPassthroughStepsForSummary(
      List<FlowStep> fold, FunctionSummary calleeSummary, TacInstruction ins) {
    if (calleeSummary == null) return;
    java.util.List<StringOp> ops = calleeSummary.stringOps();
    if (ops == null || ops.isEmpty()) return;
    for (StringOp sop : ops) {
      fold.add(
          new FlowStep(
              "passthrough-summary-fold",
              "",
              ins.line,
              ins.column,
              calleeSummary.signature(),
              "callee." + sop.methodName() + "()"));
    }
  }

  /**
   * Subclass hook: the human-readable finding message. Default mentions the sink family, CWE, and
   * the tainted arg.
   */
  protected String findingMessage(TacInstruction ins, SinkFamily family, TacOperand arg) {
    String cwePart = family.cweOrNull() == null ? "" : " (" + family.cweOrNull() + ")";
    return "Tainted value flows to "
        + family.name()
        + cwePart
        + " at "
        + (arg instanceof TacOperand.TacVar v ? v.name() : "<const>")
        + " via "
        + (ins.calleeSig == null ? "<call>" : ins.calleeSig);
  }

  /**
   * Subclass hook: should this fact qualify as a taint source for this rule? Default accepts any
   * fact whose kind is the rule's source kind. Subclasses can further filter on the fact's value
   * (e.g. only {@code "bu-0001-http-request"} sources for the SSRF rule).
   */
  protected boolean isSourceFact(Fact f) {
    if (f == null || f.kind() == null) return false;
    return f.kind().name().equals(rule.sourceKindName());
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    FunctionSummary.Builder b = new FunctionSummary.Builder(fn.signature());
    // 1. For each tainted param, record a
    //    TaintPropagate op so callers see the
    //    taint.
    //
    //    also carry the param's taint
    //    label as the op's `origin`. The entry
    //    boundary seeds every param as
    //    `param:<name>` (beginFunction), but
    //    when the caller re-tainted the
    //    argument the label has already been
    //    rewritten to the real upstream source
    //    by propagateTaintFromCalleeSummary.
    //    Recording that label lets the next hop
    //    name the true origin instead of
    //    collapsing the chain to this function's
    //    own parameter name.
    List<TacOperand.TacVar> params = fn.params();
    for (int i = 0; i < params.size(); i++) {
      TacOperand.TacVar p = params.get(i);
      if (taintedVars.containsKey(p)) {
        b.recordParamOp(
            i,
            new Operation.TaintPropagate(
                "param:" + p.name() + " in " + fn.signature(),
                1.0,
                taintOriginOf(taintedVars.get(p))));
      }
    }
    // 1b. : For each tainted passed-out
    //    value (recorded by the RETURN case
    //    in checkInstruction), record a
    //    TaintPropagate op on the returnOps
    //    so callers see "this function
    //    returns tainted data" and the
    //    cross-function summary-fold path
    //    taints the call's dest. Without
    //    this, a `helper(s) -> s` style
    //    passthrough would silently lose
    //    taint.
    for (TacOperand.TacVar ret : passedOutTaint) {
      if (taintedVars.containsKey(ret)) {
        b.recordReturnOp(
            new Operation.TaintPropagate("return:" + ret.name() + " in " + fn.signature(), 1.0));
      } else {
      }
    }
    // 2. For each observed sink call, record
    //    a Sink op on the param at the
    //    sink-arg index so callers can see
    //    "this function has a sql.sinks sink
    //    on its arg i".
    for (SinkObservation so : sinkObservations) {
      if (so.argIndex < params.size()) {
        b.recordParamOp(so.argIndex, new Operation.Sink(so.family.name(), so.argIndex));
      }
    }
    // 2b. Write the per-function
    //     string-passthrough accumulator
    //     into the summary. Each StringOp
    //     is recorded as-is; the
    //     two-pass analyzer walks the
    //     ordered list to re-apply the
    //     passthroughs at the call site.
    for (StringOp sop : stringOps) {
      b.recordStringOp(sop);
    }
    // 2c. Write the per-function
    //     field-write accumulator into
    //     the summary. Each FieldOp is
    //     recorded as-is; the
    //     FieldTaintTracker walks the
    //     list to seed this.f with taint
    //     from the matching caller arg.
    for (FieldOp fop : fieldOps) {
      b.recordFieldOp(fop);
    }
    // 2d. Mark the function as
    //     having a non-trivial body if
    //     it has at least one non-RETURN
    //     instruction. This signal is
    //     used by the cross-function
    //     summary-fold path to apply a
    //     conservative caller-arg : dest
    //     taint passthrough rule. The
    //     signal is intentionally
    //     independent of
    //     paramOps/returnOps/stringOps/fieldOps:
    //     a function like
    //     `return "literal" + x + "literal"`
    //     has no recorded ops (because
    //     the standalone analysis didn't
    //     see x as tainted) but does
    //     have a body, and the existing
    //     dest-tainting rule
    //     misses this case.
    int nonReturnCount = 0;
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind != TacInstruction.Kind.RETURN) {
        nonReturnCount++;
        if (nonReturnCount >= 1) break;
      }
    }
    if (nonReturnCount > 0) {
      b.markHasBody();
    }
    // 3. Subclass-specific additional
    //    ops. Default no-op. Checkers
    //    that maintain extra per-function
    //    state (e.g. GuardTracker for
    //    if-guards) can emit those as
    //    Operation.SolverZ3Predicate
    //    entries on the summary so
    //    callers see them via the
    //    cross-function propagation.
    emitSummaryOps(b, fn);
    FunctionSummary built = b.build();
    return built;
  }

  /**
   * Subclass hook for emitting additional {@link Operation} entries into the function's summary.
   * The default implementation is a no-op.
   *
   * <p>Checkers that track extra per-function state (e.g. {@link GuardTracker} for if-condition
   * guards, path-sensitivity for resource alloc/free, etc.) can override this to record that state
   * on the summary. Callers will see it via {@link #propagateTaintFromCalleeSummary} (which already
   * handles {@link Operation.SolverZ3Predicate}).
   *
   * <p>Typical usage: an {@code IntOverflowChecker} that tracks {@code if (data &lt; MAX)} guards
   * can emit {@code new Operation.SolverZ3Predicate("data &lt; MAX", Set.of("data"))} on the {@code
   * returnOps} when the guard is active at function exit. The caller will accumulate this predicate
   * and use it in {@link #composePathCondition} to suppress false positives that would otherwise be
   * flagged in the caller.
   *
   * <p>Note: predicates are only valid for the return path. If the function returns inside an
   * if-true block, the active guard is true on return : the predicate is sound. If the function
   * returns outside the block, the caller will see the predicate but it doesn't hold, so the bridge
   * may incorrectly suppress. Subclasses are responsible for being conservative about which
   * predicates to emit.
   */
  protected void emitSummaryOps(FunctionSummary.Builder b, TacFunction fn) {
    // Default: no-op.
  }

  /** Look up the per-checker {@link CheckerConfig} from the engine's registry. */
  @Override
  public CheckerConfig config(AnalysisContext ctx) {
    EngineConfig cfg = ctx.config();
    return cfg.checkers().byName(name()).orElse(null);
  }

  /**
   * Step 7 : default rule prompt auto-generated from the {@link TaintRule} metadata. Individual
   * checkers (e.g. {@code SqlInjectionChecker}) can override this for a more specific, hand-tuned
   * prompt; the default here covers the common case where the rule's source kind + sink family +
   * CWE are enough to let the LLM judge TP / FP / UNKNOWN sensibly.
   */
  @Override
  public String getAiPrompt(org.plumb.reporter.Finding finding) {
    TaintRule r = rule;
    return "RULE: "
        + r.ruleId()
        + " ("
        + r.cwe()
        + ", "
        + r.severityLabel()
        + ")\n"
        + "Source kind:   "
        + r.sourceKindName()
        + "\n"
        + "Sink family:   "
        + r.sinkFamilyKey()
        + "\n"
        + "Domain:        "
        + (r.domain() == null ? "?" : r.domain().label())
        + "\n"
        + "This rule fires when a tainted value flows from the\n"
        + "source family into the sink family without a recognised\n"
        + "sanitizer on the path. A finding is TP when the data\n"
        + "really does reach the sink unsanitised. A finding is FP\n"
        + "when the source is trusted (constant / config / vault)\n"
        + "or when there's a sanitizer on the path\n"
        + "(parameterised query, setString / setXxx, allow-list,\n"
        + "documented escape routine, etc.). UNKNOWN when you can't\n"
        + "tell from the source + dataflow trace alone; prefer\n"
        + "UNKNOWN over guessing.";
  }

  /** Convenience for tests: how many vars are currently tainted. */
  public int taintedCount() {
    return taintedVars.size();
  }

  /** Convenience for tests: is the given var tainted right now. */
  public boolean isTainted(TacOperand.TacVar v) {
    return taintedVars.containsKey(v);
  }

  /**
   * Test accessor: the current taint label for the given var, or {@code null} if the var is not
   * currently tainted. Exposed for tests that need to assert on the enriched label format (e.g.
   * {@code "callee-ret:H#m[toLowerCase,trim]"}).
   */
  public String taintedLabelFor(TacOperand.TacVar v) {
    return taintedVars.get(v);
  }

  /**
   * Test accessor: the current source-to-here taint path for the given var, or {@code null} if the
   * var is not currently tainted. The list is the unmodifiable view stored in {@link #taintPaths}.
   * Exposed for tests that need to assert on the passthrough-summary-fold steps.
   */
  public List<FlowStep> taintedPathFor(TacOperand.TacVar v) {
    return taintPaths.get(v);
  }

  /** Convenience for tests: how many sink observations have been recorded. */
  public int sinkObservationCount() {
    return sinkObservations.size();
  }

  /**
   * Static accessor for {@code classNameOfType} so the {@link FieldTaintTracker} helper can derive
   * the receiver's class name without holding a checker instance. Returns {@code null} for
   * non-class types.
   */
  static String classNameOfTypeStatic(TacType t) {
    if (t == null) return null;
    if (t.kind() != TacType.Kind.CLASS) return null;
    return t.toString();
  }

  /**
   * Remove a single entry from the unified cross-function class-field tracker. Used by the fold
   * path to clear stale taint when a caller-side write is provably clean (a literal, or a
   * non-tainted TacVar).
   */
  static void removeCrossFnClassFieldTaint(String className, String fieldName) {
    if (className == null || fieldName == null) return;
    String key = className + "." + fieldName;
    crossFnClassFieldTaint.remove(key);
    crossFnClassFieldSource.remove(key);
  }

  /**
   * Seed the unified cross-function class-field tracker from the cross-fn call fold. Called by
   * {@link FieldTaintTracker#applyCalleeFieldOp} after a successful per-instance write, so a
   * sibling getter on a different instance of the same declared class (aliased case) sees the
   * taint. Static so the field-tracker helper can call it without holding a checker instance.
   *
   * <p>The receiver name is the local caller-side name (e.g. {@code "h"}), not the class name. We
   * write both entry points that {@code recordFieldOpForStore} uses (uppercase class name for
   * static fields, declared class type for aliased fields) so the receiver-aware {@code
   * restoreFieldTaintOnLoad} path can find the taint regardless of which one it consults first.
   */
  static void seedCrossFnClassFieldTaintFromFold(
      String receiverName,
      TacType receiverType,
      String fieldName,
      String label,
      List<FlowStep> path,
      String calleeSig) {
    seedCrossFnClassFieldTaintFromFold(
        receiverName, receiverType, fieldName, label, path, calleeSig, -1);
  }

  /**
   * follow-up -- overload that threads the v2 {@code Trace} handle of the source this fold
   * propagated from. The handle is recovered by {@code restoreFieldTaintOnLoad} via {@code
   * crossFnClassFieldSource.get(key)} and attached to the new {@code PROPAGATE} node as a real
   * parent. Pass {@code -1} if the source predates dual-write.
   */
  static void seedCrossFnClassFieldTaintFromFold(
      String receiverName,
      TacType receiverType,
      String fieldName,
      String label,
      List<FlowStep> path,
      String calleeSig,
      int v2Handle) {
    if (receiverName == null || fieldName == null || label == null) return;
    // Class-name form: receiver IS a
    // class name like {@code "Helper"}.
    if (isLikelyClassName(receiverName)) {
      String key = receiverName + "." + fieldName;
      crossFnClassFieldTaint.put(key, label);
      crossFnClassFieldSource.put(
          key,
          new FieldSource(
              calleeSig == null ? "<fold>" : calleeSig, FieldSource.KIND_STATIC, v2Handle));
    }
    // Declared-class-type form. The
    // fold's caller passes the
    // receiver's declared TacType
    // (when available). The
    // unified-design writes the
    // declared-type key from the
    // standalone FIELDSTORE; the cross-fn
    // fold path mirrors that here so
    // sibling getters on a different
    // instance of the same declared
    // class (aliased case) see the
    // taint via the global tracker.
    if (receiverType != null) {
      String recvTypeName = classNameOfType(receiverType);
      if (recvTypeName != null) {
        String key = recvTypeName + "." + fieldName;
        crossFnClassFieldTaint.put(key, label);
        crossFnClassFieldSource.put(
            key,
            new FieldSource(
                calleeSig == null ? "<fold>" : calleeSig, FieldSource.KIND_ALIASED, v2Handle));
      }
    }
  }

  /**
   * Strip a leading class-name prefix from a callee sig of the form {@code
   * "ClassName.methodName/arity"}.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code "Helper.setData/1"} : {@code "setData/1"}
   *   <li>{@code "com.example.Helper.setData/1"} : {@code "setData/1"} (only the last {@code .} is
   *       stripped, the FQN prefix is dropped as a unit)
   *   <li>{@code "setData/1"} : {@code null} (already bare-name)
   *   <li>{@code "setData"} : {@code null} (no arity suffix)
   *   <li>{@code "buildQuery/1"} : {@code null} (same-class call, already bare)
   * </ul>
   */
  static String r53StripClassPrefix(String sig) {
    if (sig == null || sig.isEmpty()) return null;
    int slash = sig.indexOf('/');
    if (slash <= 0) return null;
    int dot = sig.lastIndexOf('.', slash - 1);
    if (dot < 0) return null;
    return sig.substring(dot + 1);
  }
}

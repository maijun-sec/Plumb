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
package org.plumb.dfa.engine;

import org.plumb.dfa.cg.CallGraph;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.fact.FactBag;
import org.plumb.dfa.summary.FunctionSummaryCache;
import org.plumb.dfa.summary.MethodSummaryCache;
import org.plumb.dfa.summary.SideEffectConsumer;
import org.plumb.tac.ir.TacProgram;

/**
 * Per-engine-run state.
 *
 * <p>Holds the program, the call graph, the engine config, the finding reporter, the {@link
 * FunctionSummaryCache} (per-run summary store, keyed by function signature, populated in RPO order
 * so callees are summarised before their callers need them), and the per-function {@link FactBag}
 * (the bag every checker reads from and writes to while analysing the current function).
 *
 * <h2>Per-function vs per-engine state</h2>
 *
 * <ul>
 *   <li><b>Per-engine state</b> (one instance shared by all functions in the program): {@link
 *       #summaryCache()}, the engine config, the call graph, the program, the finding reporter.
 *       Reset / populated by the engine between runs.
 *   <li><b>Per-function state</b> (the {@link #factBag()} is re-used but its contents are cleared
 *       by the engine at the start of every function): the bag itself lives for the whole run, but
 *       its content scope is the current function. This avoids the cost of allocating a fresh bag
 *       for every function while keeping the per-function fact isolation invariant.
 *   <li><b>Per-thread state</b> (the same bag, one per thread): the parallel worklist analyses
 *       several functions at once, so "per function" has to mean "per function ON THIS THREAD". A
 *       shared bag would have the workers erasing each other's facts mid-analysis. See the
 *       constructor for the detail.
 * </ul>
 *
 * <h2>Cross-function fact propagation</h2>
 *
 * <p>The engine calls every checker's {@link DfaChecker#summarizeFunction(AnalysisContext,
 * org.plumb.tac.ir.TacFunction)} after {@code finishFunction} and stores the merged result in
 * {@link #summaryCache()}. Callers see the callee's facts through the summary, not through a
 * separate fact store; the per-function fact bag is a working area for the engine + checkers, not a
 * cross-function API.
 */
public final class AnalysisContext {

  /**
   * The smallest method-summary cache the engine builds, whatever the program looks like.
   *
   * <p>A program with fewer functions than this gets a cache with room to spare.
   */
  private static final int METHOD_SUMMARY_CACHE_MINIMUM = 4096;

  private final TacProgram program;
  private final CallGraph callGraph;
  private final EngineConfig config;
  private final FindingReporter reporter;
  private final FunctionSummaryCache summaryCache;
  private final MethodSummaryCache methodSummaryCache;

  /**
   * The per-function fact bag, PER THREAD.
   *
   * <p>See the constructor for why the per-function state is thread-local rather than shared.
   */
  private final ThreadLocal<FactBag> factBag;

  private final org.plumb.dfa.solver.GuardStore guardStore;

  /** The per-function side-effect consumer, PER THREAD. Same reasoning as {@link #factBag}. */
  private final ThreadLocal<SideEffectConsumer> sideEffectConsumer;

  private final FieldDefUseTracker fieldDefUseTracker;

  /**
   * The WHOLE-PROGRAM facts, shared by every worker of the run.
   *
   * <p>A cross-function checker has to hold a fact about every function in the program -- "does
   * this callee throw?", "does this caller have a catch?". It used to hold those facts in an
   * INSTANCE field, which works sequentially (one instance, every function) but not in a parallel
   * run: each worker owns its own checker instances, so each instance saw only the functions its
   * worker analysed, and a lookup for a callee in another worker's slice missed.
   *
   * <p>The consequence was a capability that silently disappeared: the cross-function finding was
   * never emitted and the scan reported success.
   *
   * <p>So the facts that are about the PROGRAM live here, where every worker can see them, and the
   * facts that are about the FUNCTION being analysed stay in the checker instance.
   */
  private final ProgramFacts programFacts = new ProgramFacts();

  /**
   * The analysis budget, shared by every checker for the duration of the run.
   *
   * <p>The budget is the engine's contract with the user about how much work may be done. It is
   * consulted at the points where work can run away (per-function time, solver time, tracked-fact
   * count) and it records every cap that fires, so an incomplete analysis is visible rather than
   * silent.
   *
   * <p>Never {@code null}: a context built outside the engine gets {@link
   * AnalysisBudget#unlimited}.
   */
  private final AnalysisBudget budget;

  /**
   * The {@code System.nanoTime()} reading taken when the current function's analysis started.
   *
   * <p>Reset by the engine at each function boundary. The budget's time checks compare against
   * this, so a checker that asks "do I still have time?" gets an answer relative to the function it
   * is analysing, not to the whole run.
   */
  private volatile long currentFunctionStartNanos;

  /**
   * The guard facts proved in the function being analysed, PER THREAD.
   *
   * <p>A guard is a fact about the FUNCTION, not about a checker: "this variable is bounded here"
   * is true whoever asks. The tracker that produces them is fed the instruction sequence once per
   * function by the engine, and the snapshot it leaves behind is read by every checker.
   *
   * <p>Per THREAD for the same reason the fact bag is: the parallel worklist analyses several
   * functions at once, and one worker's function must not see another worker's guards.
   *
   * <p>{@code null} when no snapshot was taken -- either the engine did not take one (path
   * verification is off) or the checker is used standalone in a test.
   */
  private final ThreadLocal<org.plumb.dfa.taint.GuardTracker> functionGuards = new ThreadLocal<>();

  /**
   * the program's alias map, solved once per run and shared by every checker.
   *
   * <p>Alias information is an input to the rules, not a finding of its own. A checker that asks
   * "is this the same object whose null-ness I already proved?", "can this field be null?", or
   * "does this write land in the reference I sanitised?" needs the same answer, and deriving it per
   * checker guarantees they disagree. This used to be a private map inside a single rule ({@code
   * UseAfterFreeChecker}, removed in ), which is why no other rule could consult it.
   *
   * <p>{@code null} until the engine installs one, so a checker used standalone in a test does not
   * have to build a solver. Query through {@link #pointsTo()} rather than touching the field, so
   * the "no solver installed" case is impossible to mishandle.
   */
  private volatile org.plumb.dfa.points.PointsToSolver pointsToSolver;

  /**
   * the program's copy-equivalence classes, solved once per run alongside the points-to map.
   *
   * <p>Complements {@link #pointsToSolver} rather than duplicating it. Points-to answers "same
   * object?" and needs an allocation site; a method parameter has none, so the points-to answer for
   * a guarded parameter is empty and the alias question is unanswerable. Copy equivalence answers
   * "same reference?" and is exact for assignment, parameter passing, and returns.
   *
   * <p>{@code null} when not installed, same contract as the points-to solver.
   */
  private volatile org.plumb.dfa.points.CopyEquivalence copyEquivalence;

  public void setCopyEquivalence(org.plumb.dfa.points.CopyEquivalence eq) {
    this.copyEquivalence = eq;
  }

  public org.plumb.dfa.points.CopyEquivalence copyEquivalence() {
    return copyEquivalence;
  }

  /**
   * Every local of {@code signature} that may be the same <em>object or reference</em> as {@code
   * receiver}, combining both analyses.
   *
   * <p>Union of {@link #aliasesOf} (points-to: reaches through fields and containers, conservative)
   * and {@link org.plumb.dfa.points.CopyEquivalence#classOf} (copy: exact for assignment and
   * parameter passing, blind to the heap). Neither alone covers the guarded-parameter shape, which
   * is the most common one.
   *
   * <p>An empty result means "no alias information", not "no aliases".
   */
  public java.util.Set<org.plumb.tac.ir.TacOperand.TacVar> aliasClassOf(
      String signature, org.plumb.tac.ir.TacOperand.TacVar receiver) {
    java.util.Set<org.plumb.tac.ir.TacOperand.TacVar> out = new java.util.LinkedHashSet<>();
    if (signature == null || receiver == null) return out;
    org.plumb.dfa.points.CopyEquivalence eq = this.copyEquivalence;
    if (eq != null) {
      out.addAll(eq.classOf(signature, receiver));
    }
    out.addAll(aliasesOf(signature, receiver));
    return out;
  }

  /**
   * Current call-site context (set by DfaEngine before each callee initFunction).
   *
   * <p>Per THREAD, like the fact bag: the parallel worklist analyses several functions at once, and
   * the call site a checker sees belongs to the function ITS worker is analysing.
   */
  private final ThreadLocal<org.plumb.dfa.context.CallSiteContext> currentCallSiteContext =
      ThreadLocal.withInitial(() -> org.plumb.dfa.context.CallSiteContext.EMPTY);

  /**
   * The function the engine is currently analysing. Set by DfaEngine before each {@code
   * initFunction} call. Used by FindingReporter to fill in {@code filePath} on findings whose
   * checker left it empty (newer DFA checkers don't carry the path themselves -- the IR doesn't
   * either).
   */
  private final ThreadLocal<org.plumb.tac.ir.TacFunction> currentFunction = new ThreadLocal<>();

  /**
   * Enclosing class → source file path. Populated by the engine (or by the caller, see {@link
   * #registerSourcePath}) so the FindingReporter can resolve the file path of a finding when the
   * checker left it empty. For a single-file scan the map has one entry; for a merged multi-file
   * scan the engine walks every function's enclosing class and registers the path the function came
   * from.
   */
  private final java.util.Map<String, java.nio.file.Path> sourcePathByEnclosingClass =
      new java.util.HashMap<>();

  public AnalysisContext(
      TacProgram program, CallGraph callGraph, EngineConfig config, FindingReporter reporter) {
    this(
        program,
        callGraph,
        config,
        reporter,
        new FunctionSummaryCache(config.tuning()),
        new MethodSummaryCache());
  }

  /**
   * Full constructor with a pre-populated {@link FunctionSummaryCache}. Tests / advanced callers
   * (e.g. the engine's pre-scan pass) can pass a pre-populated cache; the default constructor above
   * creates a fresh one backed by the engine's tuning config.
   */
  public AnalysisContext(
      TacProgram program,
      CallGraph callGraph,
      EngineConfig config,
      FindingReporter reporter,
      FunctionSummaryCache summaryCache) {
    this(program, callGraph, config, reporter, summaryCache, null);
  }

  /**
   * Full constructor with both a pre-populated {@link FunctionSummaryCache} and a pre-populated
   * {@link MethodSummaryCache}; the default constructors above create fresh instances backed by the
   * engine's tuning config.
   */
  public AnalysisContext(
      TacProgram program,
      CallGraph callGraph,
      EngineConfig config,
      FindingReporter reporter,
      FunctionSummaryCache summaryCache,
      MethodSummaryCache methodSummaryCache) {
    this(
        program,
        callGraph,
        config,
        reporter,
        summaryCache,
        methodSummaryCache,
        new AnalysisBudget(config.tuning()));
  }

  /**
   * How many method summaries the cache may hold.
   *
   * <p>The cache holds one summary per function the engine analysed, and the engine builds exactly
   * one per function, so the program's function count is the number of entries the cache can ever
   * need. A fixed bound below that number does not bound memory: the summaries are built either
   * way, and a bound that drops them makes the call sites that need them fold nothing.
   *
   * @param program the program being analysed
   * @return the capacity to give the cache
   */
  private static int methodSummaryCapacity(TacProgram program) {
    int functions = program == null ? 0 : program.functions().size();
    return Math.max(METHOD_SUMMARY_CACHE_MINIMUM, functions);
  }

  /**
   * Full constructor including the analysis budget.
   *
   * <p>The engine passes its own budget here so that every checker in a run shares one set of
   * counters and one exhaustion record. A caller that builds a context by hand gets a fresh budget
   * derived from the tuning configuration.
   */
  public AnalysisContext(
      TacProgram program,
      CallGraph callGraph,
      EngineConfig config,
      FindingReporter reporter,
      FunctionSummaryCache summaryCache,
      MethodSummaryCache methodSummaryCache,
      AnalysisBudget budget) {
    this.program = program;
    this.callGraph = callGraph;
    this.config = config;
    this.reporter = reporter;
    this.summaryCache =
        summaryCache == null ? new FunctionSummaryCache(config.tuning()) : summaryCache;
    this.methodSummaryCache =
        methodSummaryCache == null
            ? new MethodSummaryCache(null, methodSummaryCapacity(program))
            : methodSummaryCache;
    this.budget = budget == null ? new AnalysisBudget(config.tuning()) : budget;
    this.currentFunctionStartNanos = System.nanoTime();
    // Per-function state is PER THREAD, not per context.
    //
    // WHY
    // ---
    // The bag and the side-effect consumer are cleared at every function
    // boundary by the engine, and a checker never keeps a reference past
    // the end of its function. That makes them per-FUNCTION state.
    //
    // It also makes them per-THREAD state, because the parallel worklist
    // analyses several functions at once, each on its own worker thread.
    // A single shared bag would have every worker clearing it and writing
    // to it at the same time: one worker's `clear()` would erase another
    // worker's facts mid-analysis, and the result would be findings that
    // depend on the thread interleaving.
    //
    // A ThreadLocal keeps the SEQUENTIAL semantics exactly -- within one
    // thread the sequence is still clear(), analyse, summarise -- while
    // giving each worker its own bag. The bag is re-used across
    // functions on the same thread, which is the deliberate allocation
    // trade-off the single shared bag was making.
    this.factBag = ThreadLocal.withInitial(FactBag::new);
    this.guardStore = new org.plumb.dfa.solver.GuardStore();
    this.fieldDefUseTracker = new FieldDefUseTracker();
    this.sideEffectConsumer = ThreadLocal.withInitial(SideEffectConsumer::new);
  }

  public TacProgram program() {
    return program;
  }

  public CallGraph callGraph() {
    return callGraph;
  }

  /**
   * per-function map of {@code "signature#paramIndex" -> taint origin}, populated by the engine
   * before the analysis loop via {@link #setParamOrigins(java.util.Map)}.
   *
   * <p>Reverse post-order analyses a callee before any of its callers, so a summary-based lookup at
   * sink-emit time cannot see where the value came from — the caller's summary simply does not
   * exist yet. This map is computed from the {@link org.plumb.dfa.summary.MethodSummaryCache}
   * warm-up (which the engine already runs before the analysis loop) and is therefore available for
   * every function, including callees.
   *
   * <p>Empty (never null) by default so callers can index it without a null check.
   */
  private volatile java.util.Map<String, String> paramOrigins = java.util.Map.of();

  public java.util.Map<String, String> paramOrigins() {
    return paramOrigins;
  }

  public void setParamOrigins(java.util.Map<String, String> origins) {
    this.paramOrigins = origins == null ? java.util.Map.of() : origins;
  }

  /**
   * Convenience lookup; returns {@code null} when no origin was recovered for that param.
   *
   * <p>Keyed by the bare signature: {@code "signature#paramIndex"}. The engine's origin map stores
   * the same spelling, so this is a constant-time lookup.
   */
  public String paramOrigin(String signature, int paramIndex) {
    if (signature == null) return null;
    return paramOrigins.get(signature + "#" + paramIndex);
  }

  /**
   * parameters whose every observed call site passes a value the analysis can prove clean.
   *
   * <p>Keyed the same way as {@link #paramOrigins}: {@code "signature#paramIndex"}.
   *
   * <h3>Why this exists</h3>
   *
   * <p>Every parameter is seeded as a taint source at the function entry boundary, so a method
   * whose parameter reaches a sink reports regardless of what any caller passed. That is a
   * defensible default for a method nobody calls — the entry boundary really is a trust boundary —
   * but it makes "the caller sanitised the value" inexpressible, so every such negative case fails
   * and no amount of precision work elsewhere can fix it.
   *
   * <h3>The contract, and why it is narrow on purpose</h3>
   *
   * <p>An entry is present only when the parameter has at least one resolved call site and
   * <em>every</em> one of them passes a literal. A literal is the only form of positive proof
   * available here: the absence of a taint label on a variable is <em>not</em> proof of
   * cleanliness, because a variable only gains a label once something tainted reaches it, so an
   * unlabelled variable is far more often "unanalysed" than "safe".
   *
   * <p>Absent means "unknown", and every consumer must keep its previous behaviour on absent.
   */
  private volatile java.util.Map<String, Boolean> paramCleanliness = java.util.Map.of();

  public java.util.Map<String, Boolean> paramCleanliness() {
    return paramCleanliness;
  }

  public void setParamCleanliness(java.util.Map<String, Boolean> clean) {
    this.paramCleanliness = clean == null ? java.util.Map.of() : clean;
  }

  /**
   * Is this parameter known to receive only provably-clean values?
   *
   * @return {@code false} when unknown, which callers must treat as "no information" rather than as
   *     "dirty".
   */
  public boolean isParamAlwaysClean(String signature, int paramIndex) {
    return Boolean.TRUE.equals(paramCleanliness.get(signature + "#" + paramIndex));
  }

  public EngineConfig config() {
    return config;
  }

  public FindingReporter reporter() {
    return reporter;
  }

  public FunctionSummaryCache summaryCache() {
    return summaryCache;
  }

  /**
   * The per-engine cache of {@link org.plumb.dfa.summary.MethodSummary} objects, populated by the
   * engine in RPO order immediately after each function is analysed (in parallel with {@link
   * #summaryCache()}, but using the IR-derived {@link org.plumb.dfa.summary.MethodSummaryBuilder}
   * rather than per-checker observations). Caller-side checkers consult this at every {@code CALL}
   * instruction via the {@link org.plumb.dfa.summary.SummaryApplier} to fold the callee's return
   * value and record the callee's side effects.
   */
  public MethodSummaryCache methodSummaryCache() {
    return methodSummaryCache;
  }

  /**
   * The per-function fact bag, for the CALLING THREAD.
   *
   * <p>The engine clears it at the start of every function; checkers read facts from it (e.g. "is
   * operand {@code x} null?") and write to it (e.g. "I just observed that {@code x} comes from user
   * input"). Cross-function propagation is via {@link #summaryCache()}, not via the bag.
   *
   * <p>Each thread has its own bag, so the parallel worklist's workers cannot erase each other's
   * facts. Within one thread the sequence is exactly what it always was.
   */
  public FactBag factBag() {
    return factBag.get();
  }

  /**
   * Starts a new function: clears the per-function state and resets the budget's per-function
   * accounting.
   *
   * <p>The fact bag, the side-effect consumer and the budget's fact counter have the same scope:
   * the function being analysed. Clearing them together keeps that scope honest -- a counter that
   * outlived the bag it guards would keep charging one function's facts against the next function's
   * cap, and the cap would then mean "the whole run" while claiming to mean "one function".
   *
   * <p>Each of the three is per THREAD, so this is called on the thread that analyses the function.
   */
  public void beginFunction() {
    factBag.get().clear();
    sideEffectConsumer.get().clear();
    budget.beginFunction();
  }

  /**
   * Cross-checker guard store. The engine creates one per analysis run; each checker can {@code
   * add} a guard via the {@code emitSummaryOps} hook (or directly in {@code checkInstruction}) and
   * any other checker can query {@link org.plumb.dfa.solver.GuardStore#impliedGuard}.
   */
  public org.plumb.dfa.solver.GuardStore guardStore() {
    return guardStore;
  }

  /**
   * Program-level field def/use tracker. The cross-function {@code UnusedFieldChecker} consults
   * this to suppress findings on private fields that are read elsewhere in the program.
   */
  public FieldDefUseTracker fieldDefUseTracker() {
    return fieldDefUseTracker;
  }

  /**
   * The WHOLE-PROGRAM facts, shared by every worker of the run.
   *
   * <p>A cross-function checker records here the facts that are about the PROGRAM -- "this function
   * throws", "this function has a catch" -- and reads them back at the end of the run. Keeping them
   * here rather than in an instance field is what makes them survive the parallel worklist: each
   * worker owns its own checker instances, so an instance field would only ever hold the facts that
   * worker's own slice produced.
   *
   * <p>See {@link ProgramFacts} for the details.
   */
  public ProgramFacts programFacts() {
    return programFacts;
  }

  /**
   * Install the program's alias map. Engine-internal: the engine solves points-to once before the
   * analysis loop and publishes it here so every checker reads the same answer.
   */
  public void setPointsToSolver(org.plumb.dfa.points.PointsToSolver solver) {
    this.pointsToSolver = solver;
  }

  /** The shared alias map, or {@code null} when the engine did not install one. */
  public org.plumb.dfa.points.PointsToSolver pointsToSolver() {
    return pointsToSolver;
  }

  /**
   * Convenience: may these two locals of {@code signature} be the same object?
   *
   * <p>Returns {@code false} when no alias map is installed or when either reference is unknown --
   * "unknown" must never be reported as "aliases", or every checker that consults this inverts its
   * own precision.
   */
  public boolean mayAlias(
      String signature,
      org.plumb.tac.ir.TacOperand.TacVar a,
      org.plumb.tac.ir.TacOperand.TacVar b) {
    org.plumb.dfa.points.PointsToSolver s = this.pointsToSolver;
    return s != null && s.mayAlias(signature, a, b);
  }

  /**
   * every local of {@code signature} that may be the same object as {@code receiver}.
   *
   * <p>Returns an <em>empty</em> set when no alias map is installed or the receiver is unknown.
   * That empty set means "no alias information", not "no aliases" — a checker must fall back to its
   * behaviour without alias data rather than conclude the reference is provably unshared.
   */
  public java.util.Set<org.plumb.tac.ir.TacOperand.TacVar> aliasesOf(
      String signature, org.plumb.tac.ir.TacOperand.TacVar receiver) {
    org.plumb.dfa.points.PointsToSolver s = this.pointsToSolver;
    if (s == null || receiver == null) return java.util.Set.of();
    return s.aliasesOf(signature, receiver, org.plumb.dfa.points.AbstractObject.Context.empty());
  }

  /**
   * the abstract objects a local of {@code signature} may point at.
   *
   * <p>Added because a consumer that transfers a guard along aliases needs more than "may these two
   * be the same object": it has to know whether the guarded name's fact set <em>covers</em> the
   * guarded-for name's. {@code mayAlias} answers "is there any overlap", which is too weak to
   * justify suppressing a finding -- a may-relation that overlaps in one object still leaves the
   * other objects unguarded.
   *
   * <p>Returns an empty set when no solver is installed or the reference is unknown, which again
   * means "no information" rather than "points to nothing".
   */
  public java.util.Set<org.plumb.dfa.points.AbstractObject.Obj> pointsTo(
      String signature, org.plumb.tac.ir.TacOperand.TacVar v) {
    org.plumb.dfa.points.PointsToSolver s = this.pointsToSolver;
    if (s == null || v == null) return java.util.Set.of();
    return s.pointsTo(signature, v, org.plumb.dfa.points.AbstractObject.Context.empty());
  }

  /**
   * may this local hold the null literal?
   *
   * <p>{@code false} means "no null path was found", which is <em>not</em> a proof of non-null: an
   * unmodelled write leaves no trace, so a checker must keep its normal conservative behaviour when
   * this answers false. Only the engine's own guard tracker and the points-to solver's explicit
   * null facts together make a reference provably safe.
   */
  public boolean mayBeNull(String signature, org.plumb.tac.ir.TacOperand.TacVar v) {
    org.plumb.dfa.points.PointsToSolver s = this.pointsToSolver;
    return s != null
        && s.mayBeNull(signature, v, org.plumb.dfa.points.AbstractObject.Context.empty());
  }

  /**
   * Per-function {@link SideEffectConsumer}, for the CALLING THREAD. The engine clears it at the
   * start of every function. Checkers consult it to learn which operands have been invalidated by a
   * side-effect CALL during the current function's analysis.
   *
   * <p>Each thread has its own consumer, for the same reason the fact bag does.
   */
  public SideEffectConsumer sideEffectConsumer() {
    return sideEffectConsumer.get();
  }

  /**
   * Current call-site context. The DfaEngine sets this just before each callee's {@code
   * initFunction} is called; checkers can read it to discover what the caller already knew.
   */
  public org.plumb.dfa.context.CallSiteContext currentCallSiteContext() {
    return currentCallSiteContext.get();
  }

  /**
   * Set the current call-site context. Engine- internal; checkers should not call this directly.
   */
  public void setCurrentCallSiteContext(org.plumb.dfa.context.CallSiteContext ctx) {
    this.currentCallSiteContext.set(
        ctx == null ? org.plumb.dfa.context.CallSiteContext.EMPTY : ctx);
  }

  /**
   * Set the function currently being analysed. Engine-internal; called by DfaEngine before each
   * {@code initFunction} so the {@link FindingReporter} can fill in {@code filePath} for findings
   * whose checker left it empty.
   */
  public void setCurrentFunction(org.plumb.tac.ir.TacFunction fn) {
    // ThreadLocal so the parallel worklist's
    // worker pool doesn't race on a single field.
    // Each worker thread now sees its own
    // currentFunction, so FindingReporter.report()
    // resolves to the function currently being
    // analysed on THIS worker (not the most recent
    // write from any worker). Pre-the field
    // was a plain reference that any worker could
    // overwrite; the synchronized report() block
    // didn't prevent Worker B's analyseOneFunction
    // from racing with Worker A's reading of
    // currentFunction.
    this.currentFunction.set(fn);
  }

  /**
   * Marks the start of the current function's analysis, for the budget's time checks.
   *
   * <p>Called by the engine at each function boundary. The budget compares elapsed time against
   * this reading, so a per-function timeout means "this function took too long", not "the whole run
   * took too long".
   */
  public void startFunctionTimer() {
    this.currentFunctionStartNanos = System.nanoTime();
  }

  /**
   * The guard tracker holding the facts proved in the current function, or {@code null} when no
   * snapshot was taken.
   *
   * <p>The engine installs one before the checkers run, so every checker reads the same facts
   * instead of each deriving its own -- which is what made two checkers disagree about whether a
   * value was guarded.
   */
  public org.plumb.dfa.taint.GuardTracker functionGuards() {
    return this.functionGuards.get();
  }

  /** Install the guard snapshot for the current function, replacing any previous one. */
  public void setFunctionGuards(org.plumb.dfa.taint.GuardTracker guards) {
    if (guards == null) {
      this.functionGuards.remove();
    } else {
      this.functionGuards.set(guards);
    }
  }

  /**
   * The index of the instruction being analysed, within the current function.
   *
   * <p>PER THREAD, for the same reason the guard snapshot is: the parallel worklist analyses
   * several functions at once, and one worker's position must not be visible to another worker.
   *
   * <p>This is what makes a PATH-scoped path condition possible. The guards in force at a sink are
   * the ones established by the branches that lead to it, and that is a question about the
   * instruction's POSITION in the function — not about the function as a whole. The engine sets
   * this before each instruction so a checker can ask "which guards hold here?".
   */
  private final ThreadLocal<Integer> currentInstructionIndex = new ThreadLocal<>();

  /** The index of the instruction being analysed, or {@code -1} when it is not known. */
  public int currentInstructionIndex() {
    Integer idx = currentInstructionIndex.get();
    return idx == null ? -1 : idx;
  }

  /**
   * Record the index of the instruction being analysed.
   *
   * @param index the instruction's index within the current function, or {@code -1} to clear it
   */
  public void setCurrentInstructionIndex(int index) {
    if (index < 0) {
      currentInstructionIndex.remove();
    } else {
      currentInstructionIndex.set(index);
    }
  }

  /**
   * The {@code System.nanoTime()} reading taken when the current function's analysis started.
   *
   * <p>Exposed so that a checker can compute its own remaining time against the same origin the
   * budget uses, rather than starting its own clock and measuring a different interval.
   */
  public long currentFunctionStartNanos() {
    return currentFunctionStartNanos;
  }

  /**
   * The analysis budget for this run.
   *
   * <p>Never {@code null}. A checker that wants to know whether it may keep working asks this
   * object; the engine reads the same object to decide when to stop.
   */
  public AnalysisBudget budget() {
    return budget;
  }

  /**
   * Whether the current function still has time to run, according to the budget.
   *
   * <p>Convenience for checkers that do expensive work and want to bail out rather than overrun the
   * per-function timeout.
   *
   * @return true if the analysis may continue
   */
  public boolean withinTimeBudget() {
    org.plumb.tac.ir.TacFunction fn = currentFunction();
    String subject = fn == null ? "" : fn.signature();
    return budget.checkPerFunctionTime(currentFunctionStartNanos, subject);
  }

  /**
   * The function currently being analysed, or {@code null} if no function is being visited
   * (init/finalize phase). Used by FindingReporter to resolve the {@code filePath} on a finding
   * whose checker didn't set it.
   */
  public org.plumb.tac.ir.TacFunction currentFunction() {
    // Read the per-thread value. Falls back
    // to null if no function is currently being
    // analysed on the calling thread.
    return currentFunction.get();
  }

  /**
   * The language of the function currently being analysed.
   *
   * <p><b>Why this is asked of the CONTEXT and not of the caller.</b> A rule that says an
   * ARGUMENT's or a RECEIVER's taint reaches the RESULT is written for one language's library, and
   * the engine has to know which language the code in front of it is written in before it applies
   * such a rule. The function being analysed is the only thing that carries that answer, and the
   * context is what holds it -- so the context is what answers the question.
   *
   * @return the language, or {@link org.plumb.tac.ir.SourceLanguage#UNKNOWN} when no function is
   *     being analysed
   */
  public org.plumb.tac.ir.SourceLanguage language() {
    org.plumb.tac.ir.TacFunction fn = currentFunction();
    return fn == null ? org.plumb.tac.ir.SourceLanguage.UNKNOWN : fn.sourceLanguage();
  }

  /**
   * Register a source file path for an enclosing class. Called by DfaEngine (which iterates the
   * program's functions and uses each function's enclosing class to look up the file path) and by
   * the ScanRunner (which can pre-populate the map for single-file scans where {@code
   * program.sourcePath()} gives the only file path).
   */
  public void registerSourcePath(String enclosingClass, java.nio.file.Path sourcePath) {
    if (enclosingClass == null || enclosingClass.isEmpty() || sourcePath == null) return;
    sourcePathByEnclosingClass.put(enclosingClass, sourcePath);
  }

  /**
   * Look up the source file path for a given enclosing class. Returns {@code null} if no path is
   * registered for that class. The reporter uses this to fill in {@code filePath} on a finding
   * whose checker left it empty.
   */
  public String sourcePathForEnclosingClass(String enclosingClass) {
    if (enclosingClass == null) return null;
    java.nio.file.Path p = sourcePathByEnclosingClass.get(enclosingClass);
    return p == null ? null : p.toString();
  }

  /**
   * Look up the source file path for a function SIGNATURE.
   *
   * <p>The signature is the identity that is ALWAYS present. A TOP-LEVEL function -- a Kotlin or
   * Scala file-level function, or a Java file's package-private helper declared outside any class
   * -- has NO enclosing class, so the enclosing-class lookup cannot resolve it and the finding is
   * reported with {@code file="unknown"}.
   *
   * <p>The signature is registered alongside the enclosing class for exactly that reason.
   *
   * @param signature the function's signature
   * @return the source path, or {@code null} when none is registered
   */
  public String sourcePathForSignature(String signature) {
    if (signature == null) return null;
    java.nio.file.Path p = sourcePathByEnclosingClass.get(signature);
    return p == null ? null : p.toString();
  }

  /**
   * The engine's {@link org.plumb.dfa.resolve.StaticFieldResolver} for resolving {@code public
   * static final} initializers. Set by the engine after {@link
   * org.plumb.dfa.resolve.ProjectCompilationContext#index()} is called; defaults to {@code null}
   * for tests that build an {@link AnalysisContext} directly (in which case the field-LOAD path
   * falls back to the orphan-SOURCE shape -- same behaviour as pre-).
   */
  private volatile org.plumb.dfa.resolve.StaticFieldResolver staticFields;

  /**
   * Install the {@link org.plumb.dfa.resolve.StaticFieldResolver} used by FIELDLOAD resolution.
   * Volatile so a checker running on a different thread (parallel analysis) sees the published
   * value on its next read.
   */
  public void setStaticFieldResolver(org.plumb.dfa.resolve.StaticFieldResolver r) {
    this.staticFields = r;
  }

  /**
   * Return the {@link org.plumb.dfa.resolve.StaticFieldResolver} previously installed via {@link
   * #setStaticFieldResolver}, or {@code null} if none was installed. Callers must null-check.
   */
  public org.plumb.dfa.resolve.StaticFieldResolver staticFields() {
    return staticFields;
  }
}

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

  private final TacProgram program;
  private final CallGraph callGraph;
  private final EngineConfig config;
  private final FindingReporter reporter;
  private final FunctionSummaryCache summaryCache;
  private final MethodSummaryCache methodSummaryCache;
  private final FactBag factBag;
  private final org.plumb.dfa.solver.GuardStore guardStore;
  private final SideEffectConsumer sideEffectConsumer;
  private final FieldDefUseTracker fieldDefUseTracker;

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

  /** Current call-site context (set by DfaEngine before each callee initFunction). */
  private org.plumb.dfa.context.CallSiteContext currentCallSiteContext =
      org.plumb.dfa.context.CallSiteContext.EMPTY;

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
    this.program = program;
    this.callGraph = callGraph;
    this.config = config;
    this.reporter = reporter;
    this.summaryCache =
        summaryCache == null ? new FunctionSummaryCache(config.tuning()) : summaryCache;
    this.methodSummaryCache =
        methodSummaryCache == null ? new MethodSummaryCache() : methodSummaryCache;
    // One bag, cleared at every function
    // boundary by the engine. Re-using the
    // allocation across functions is a
    // deliberate trade-off: we save the
    // cost of re-allocating a fresh bag for
    // every function, and checkers never need
    // to keep a reference past finishFunction.
    this.factBag = new FactBag();
    this.guardStore = new org.plumb.dfa.solver.GuardStore();
    this.fieldDefUseTracker = new FieldDefUseTracker();
    // Per-function side-effect
    // consumer. The engine clears it (along
    // with the bag) at the start of every
    // function, so its lifetime matches
    // the bag's.
    this.sideEffectConsumer = new SideEffectConsumer();
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

  /** Convenience lookup; returns {@code null} when no origin was recovered for that param. */
  public String paramOrigin(String signature, int paramIndex) {
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
   * The per-function fact bag. The engine clears it at the start of every function; checkers read
   * facts from it (e.g. "is operand {@code x} null?") and write to it (e.g. "I just observed that
   * {@code x} comes from user input"). Cross-function propagation is via {@link #summaryCache()},
   * not via the bag.
   */
  public FactBag factBag() {
    return factBag;
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
   * Per-function {@link SideEffectConsumer}. The engine creates one fresh consumer at the start of
   * every function and discards it at the end. Checkers consult it to learn which operands have
   * been invalidated by a side-effect CALL during the current function's analysis.
   */
  public SideEffectConsumer sideEffectConsumer() {
    return sideEffectConsumer;
  }

  /**
   * Current call-site context. The DfaEngine sets this just before each callee's {@code
   * initFunction} is called; checkers can read it to discover what the caller already knew.
   */
  public org.plumb.dfa.context.CallSiteContext currentCallSiteContext() {
    return currentCallSiteContext;
  }

  /**
   * Set the current call-site context. Engine- internal; checkers should not call this directly.
   */
  public void setCurrentCallSiteContext(org.plumb.dfa.context.CallSiteContext ctx) {
    this.currentCallSiteContext = ctx == null ? org.plumb.dfa.context.CallSiteContext.EMPTY : ctx;
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

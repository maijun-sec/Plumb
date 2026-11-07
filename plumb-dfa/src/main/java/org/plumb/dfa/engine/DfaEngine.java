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

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.plumb.dfa.cg.CallGraph;
import org.plumb.dfa.cg.CallGraphStrategy;
import org.plumb.dfa.cg.CallGraphStrategyFactory;
import org.plumb.dfa.cg.CallSite;
import org.plumb.dfa.cg.FunctionNode;
import org.plumb.dfa.cg.FunctionSignature;
import org.plumb.dfa.config.CheckerConfig;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.resolve.SourcePathResolver;
import org.plumb.dfa.resolve.StaticFieldResolver;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.summary.SummaryApplier;
import org.plumb.dfa.taint.FrameworkEntryPoints;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacBasicBlock;
import org.plumb.tac.ir.TacCfg;
import org.plumb.tac.ir.TacCfgBuilder;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top-level DFA orchestrator.
 *
 * <h2>Single-threaded pipeline</h2>
 *
 * <ol>
 *   <li>Build the {@link CallGraph} via the configured {@link CallGraphStrategy}.
 *   <li>Construct an {@link AnalysisContext}.
 *   <li>For each function in {@link CallGraph#reversePostOrder()}: {@code initFunction :
 *       checkInstruction* : finishFunction : summarizeFunction}, then put the summary in the cache.
 *   <li>{@code finish()} for every checker.
 * </ol>
 *
 * <h2>Function summaries</h2>
 *
 * <p>After every function's analysis phase, the engine collects the per-checker {@link
 * FunctionSummary}, merges them with {@link FunctionSummary#merge(FunctionSummary)}, and stores the
 * merged result in the shared {@link AnalysisContext#summaryCache()}. A caller-side checker that
 * hits a CALL can then look up the callee and consult its summary without re-analysing the body.
 *
 * <h2>Parallel pipeline + BB-RPO</h2>
 *
 * <p>The engine has two execution modes:
 *
 * <ul>
 *   <li>{@link #runParallel(TacProgram, int)} : worklist-based parallel pipeline: leaves of the
 *       call graph start in the initial {@code readyQ}; each function completes and decrements its
 *       callers' dependency counter; when a caller's count hits zero it's enqueued. A bounded
 *       {@link ExecutorService} runs the batch.
 *   <li>{@link #bbRpoOrder(TacFunction)} : BB-RPO, and <b>forward</b> inside each block. Blocks are
 *       emitted in reverse post-order; the instructions inside a block keep their original forward
 *       order. Single-block functions (the common case for small getters / setters) therefore get
 *       exactly the linear instruction order, so this is backward-compatible for the simple case.
 * </ul>
 *
 * <h3>Why BB-RPO and not just RPO</h3>
 *
 * <p>At a CFG join point, a forward-flow analysis (the taint / nullability / range lattices all run
 * forward) needs all predecessors' values to be merged before it processes the join block. RPO
 * guarantees this: every block is visited after all its predecessors. Linear instruction order
 * doesn't have this guarantee : without BB-RPO the engine would have to rely on each checker
 * maintaining its own forward-flow worklist, duplicating the scheduling work.
 *
 * <h3>Why the intra-block direction stays forward</h3>
 *
 * <p>The instruction order within a block is deliberately NOT reversed. Every checker in this
 * engine propagates a forward lattice -- taint labels, nullable facts, interval bounds -- so
 * reversing a block would visit uses before defs and silently break the propagation those checkers
 * depend on. The engine's summary step reads the facts the forward walk already produced rather
 * than doing its own backward use-def walk.
 *
 * <h3>Backward compatibility</h3>
 *
 * <ul>
 *   <li>The 4-arg constructor sets {@code parallelism = 0} : the engine runs sequentially.
 *       Sequential behaviour is preserved bit-for-bit for single-block functions.
 *   <li>Sequential mode uses the same {@code bbRpoOrder} helper, so the instruction iteration order
 *       is "BB-RPO, forward inside each block" for both sequential and parallel. Tests that relied
 *       on the linear order of a single-block function (the common case) see the same order; tests
 *       that relied on the linear order of a multi-block function (rare) may observe a different
 *       order : the expected change.
 *   <li>The engine's public API ({@code run}, {@code name}, constructor signatures) is unchanged
 *       for existing callers.
 * </ul>
 */
public final class DfaEngine {

  private static final Logger LOG = LoggerFactory.getLogger(DfaEngine.class);

  private final EngineConfig config;
  private final List<DfaChecker> checkers;
  private final String callGraphStrategyName;
  private final Path callGraphCachePath;

  /** 0 = sequential; >0 = parallel with this many worker threads. */
  private final int parallelism;

  /**
   * The factory that produces a FRESH set of checkers for one worker thread.
   *
   * <p><b>Why the parallel path cannot share the checkers.</b> A checker is not stateless between
   * functions: {@link DfaChecker#initFunction(AnalysisContext, org.plumb.tac.ir.TacFunction)}
   * clears the per-function accumulators (the tainted variables, the taint paths, the field
   * tracker, the trace builder) and the instruction walk fills them back in. Two workers sharing
   * one checker instance would have one worker's {@code clear()} erase the other worker's facts in
   * the middle of its walk, so the findings would depend on the thread interleaving -- and the run
   * would lose findings, not gain them.
   *
   * <p>So each worker gets its OWN checker instances, built by this factory. The instances are
   * equivalent (same rules, same config); they are simply not shared.
   *
   * <p>{@code null} means "no factory": the parallel path then runs with the shared list, which is
   * only safe when the caller knows the checkers are thread-safe. {@link #DfaEngine(EngineConfig,
   * List, String, Path, int, java.util.function.Supplier)} is the constructor that takes one.
   */
  private final java.util.function.Supplier<List<DfaChecker>> checkerFactory;

  /**
   * Per-function source path map, keyed by enclosing class. Set by the caller (e.g. ScanRunner)
   * before {@link #run} to enable filePath-filling for merged programs where the synthetic {@code
   * MergedProgram.asProgram()} has no single source path. When null, the engine falls back to
   * {@code program.sourcePath()} (which works for single-file scans).
   */
  private java.util.Map<String, java.nio.file.Path> functionSourcePaths;

  /**
   * The {@link org.plumb.dfa.resolve.StaticFieldResolver} installed on every {@link
   * AnalysisContext} the engine builds during {@link #run}. Set by the caller (e.g. ScanRunner via
   * {@link ProjectCompilationContext#staticFields()}) before {@link #run}; {@code null} means the
   * engine skips the const-static-field resolution branch and FIELDLOAD falls back to the
   * orphan-SOURCE shape (same behaviour as pre-). The field is read by every {@code new
   * AnalysisContext(...)} site at {@link #run}-time and installed via {@link
   * AnalysisContext#setStaticFieldResolver}.
   */
  private org.plumb.dfa.resolve.StaticFieldResolver staticFieldResolver;

  /**
   * The budget for the current run, created fresh per {@code run} invocation.
   *
   * <p>It is created here rather than held for the engine's lifetime because a budget is a property
   * of <em>a run</em>: the counters say how much work <em>this</em> analysis did, and the
   * exhaustion record says where <em>this</em> analysis stopped. Reusing a budget across runs would
   * make the second run's counters meaningless.
   *
   * <p>Volatile so the parallel workers see the published instance.
   */
  private volatile AnalysisBudget budget;

  /**
   * The time-bounded solver bridge for the current run, if one was created.
   *
   * <p>Kept so the run can report how many solver queries hit the timeout.
   */
  private volatile org.plumb.dfa.solver.TimeBoundedSolverBridge boundedSolver;

  /**
   * The module boundary of this run, when the analysis is limited to one module.
   *
   * <p>{@code null} means "the whole project is in scope". See {@link ModuleScope} for why a
   * single-module analysis still reads the other modules.
   */
  private volatile ModuleScope moduleScope;

  /**
   * Limit the analysis to one module.
   *
   * <p>The other modules are still read: their functions are summarised so the call graph keeps
   * crossing the boundary, and the taint keeps flowing into the module. What changes is that a
   * finding is only reported when its SINK is inside the module.
   *
   * @param module the module id; {@code null} or blank means "the whole project"
   * @return this engine
   */
  public DfaEngine withModuleScope(String module) {
    this.moduleScope = ModuleScope.of(module);
    return this;
  }

  /** The module scope of this run. Never null. */
  public ModuleScope moduleScope() {
    ModuleScope s = this.moduleScope;
    return s == null ? ModuleScope.all() : s;
  }

  /**
   * The per-run cache of control-flow graphs and analysis orders.
   *
   * <p>The analysis order is a whole-function walk, and it used to be recomputed for every checker.
   * With the registered checker count in the dozens, a function was walked that many times to
   * produce that many identical lists.
   */
  private volatile FunctionOrderCache orderCache;

  /**
   * The order cache for the current run.
   *
   * <p>Created per run, because a cache that outlived the run would keep the analysed functions'
   * control-flow graphs alive.
   */
  public FunctionOrderCache orderCache() {
    return orderCache;
  }

  /**
   * The budget for the current run, or {@code null} before the run starts.
   *
   * <p>Exposed so a caller can read the exhaustion report after a run.
   */
  public AnalysisBudget budget() {
    return budget;
  }

  /**
   * The exhaustion record for the current run.
   *
   * <p>Returns {@code null} before a run has started. A non-null report with {@link
   * AnalysisBudget.BudgetReport#complete()} false means the analysis was incomplete -- and it says
   * which dimension ran out and on what.
   */
  public AnalysisBudget.BudgetReport budgetReport() {
    AnalysisBudget b = budget;
    return b == null ? null : b.report();
  }

  /**
   * Install the {@link org.plumb.dfa.resolve.StaticFieldResolver} used by the engine. Volatile so
   * parallel workers see the published value. Idempotent.
   */
  public void setStaticFieldResolver(org.plumb.dfa.resolve.StaticFieldResolver r) {
    this.staticFieldResolver = r;
  }

  /**
   * The installed {@link org.plumb.dfa.resolve.StaticFieldResolver}, or {@code null}.
   *
   * <p><b>Why a test needs to see this.</b> Whether the engine reads source is not observable in
   * its results: a resolver that is installed and one that is not both produce a working analysis,
   * and the difference only shows up as slightly different FIELDLOAD shapes. That makes the
   * boundary untestable, and an untestable boundary is a boundary that erodes. This accessor makes
   * it visible: {@code null} means "this analysis reads no source", non-{@code null} means it does.
   *
   * @return the installed resolver, or {@code null} if none is installed
   */
  public org.plumb.dfa.resolve.StaticFieldResolver staticFieldResolverForTest() {
    return staticFieldResolver;
  }

  public DfaEngine(
      EngineConfig config,
      List<DfaChecker> checkers,
      String callGraphStrategyName,
      Path callGraphCachePath) {
    this(config, checkers, callGraphStrategyName, callGraphCachePath, 0);
  }

  /**
   * Full constructor.
   *
   * <ul>
   *   <li>{@code 0} : sequential. Same as the 4-arg constructor.
   *   <li>{@code >0} : parallel. The engine spins up an {@link ExecutorService} with this many
   *       worker threads and runs a worklist over the call graph: as soon as a function's callees
   *       are all summarised, it joins the ready queue.
   * </ul>
   *
   * <p>Negative values are clamped to 0 (sequential).
   *
   * <p><b>A parallel run needs its own checkers.</b> A checker carries per-function state, and
   * {@link DfaChecker#initFunction(AnalysisContext, org.plumb.tac.ir.TacFunction)} clears it at
   * every function boundary. Two workers sharing one checker instance would have one worker's clear
   * erase the other's facts mid-walk, so the findings would depend on the thread interleaving --
   * and the run would LOSE findings. This constructor therefore REFUSES a parallel run: use {@link
   * #DfaEngine(EngineConfig, List, String, Path, int, java.util.function.Supplier)} and pass a
   * factory that builds a fresh set per worker.
   */
  public DfaEngine(
      EngineConfig config,
      List<DfaChecker> checkers,
      String callGraphStrategyName,
      Path callGraphCachePath,
      int parallelism) {
    this(config, checkers, callGraphStrategyName, callGraphCachePath, parallelism, null, false);
  }

  /**
   * Full constructor with a per-worker checker factory.
   *
   * <p>The factory is what makes the parallel path CORRECT rather than merely fast: a checker
   * carries per-function state, so two workers cannot share one instance. The factory produces a
   * fresh, equivalent set for each worker thread.
   *
   * <p>When the factory is {@code null} the engine keeps the single shared list. That is correct
   * for the sequential path, and for a parallel run whose checkers are known to be thread-safe.
   *
   * @param checkerFactory produces a fresh checker list for one worker; {@code null} to share
   */
  public DfaEngine(
      EngineConfig config,
      List<DfaChecker> checkers,
      String callGraphStrategyName,
      Path callGraphCachePath,
      int parallelism,
      java.util.function.Supplier<List<DfaChecker>> checkerFactory) {
    this(
        config,
        checkers,
        callGraphStrategyName,
        callGraphCachePath,
        parallelism,
        checkerFactory,
        true);
  }

  /**
   * The single constructor every public constructor funnels into.
   *
   * @param factoryProvided whether the caller used the factory-taking constructor. It decides
   *     whether a parallel run without a factory is a hard error.
   */
  private DfaEngine(
      EngineConfig config,
      List<DfaChecker> checkers,
      String callGraphStrategyName,
      Path callGraphCachePath,
      int parallelism,
      java.util.function.Supplier<List<DfaChecker>> checkerFactory,
      boolean factoryProvided) {
    this.config = Objects.requireNonNull(config, "config");
    this.checkers = List.copyOf(Objects.requireNonNull(checkers, "checkers"));
    this.callGraphStrategyName = callGraphStrategyName == null ? "global" : callGraphStrategyName;
    this.callGraphCachePath = callGraphCachePath;
    this.parallelism = Math.max(0, parallelism);
    if (this.parallelism > 0 && checkerFactory == null && !factoryProvided) {
      throw new IllegalArgumentException(
          "a parallel analysis needs a checker factory: a checker carries per-function state, and"
              + " two workers sharing one instance would erase each other's facts mid-walk, so"
              + " the findings would depend on the thread interleaving. Use the constructor that"
              + " takes a Supplier<List<DfaChecker>> and return a fresh set per worker, or run"
              + " sequentially.");
    }
    this.checkerFactory = checkerFactory;
  }

  /**
   * The checkers one worker thread should use.
   *
   * <p>With a factory, every worker gets its own instances, because the checkers carry per-function
   * state and cannot be shared. Without one, the shared list is used as-is.
   */
  private List<DfaChecker> checkersForWorker() {
    if (checkerFactory == null) return checkers;
    List<DfaChecker> fresh = checkerFactory.get();
    return fresh == null ? checkers : fresh;
  }

  /** The configured parallelism (0 = sequential). */
  public int parallelism() {
    return parallelism;
  }

  /**
   * The registered checkers (read-only). Used by the runner to look up the {@link DfaChecker} for a
   * given finding (matched on rule name) and call its {@link DfaChecker#getAiPrompt(Finding)} so
   * the LLM dedup pass can ask the rule for its own prompt.
   */
  public List<DfaChecker> checkers() {
    return checkers;
  }

  /**
   * Register per-enclosing-class source file paths. Used by the merged scan path: the synthetic
   * {@code MergedProgram.asProgram()} has no single source path, so the ScanRunner walks the
   * underlying sources and passes the per-file path map here. The engine applies the map during
   * {@code registerSourcePaths} (called from {@code run}).
   *
   * <p><b>This does NOT read any source.</b> The map is metadata: it says which file a function
   * came from, so a finding can name it. Reading the file back would break the source-less contract
   * this class exists to support; see {@link #withStaticFieldResolverRoot(Path)} for the one path
   * that deliberately does look at source, and why it is opt-in.
   */
  public DfaEngine withFunctionSourcePaths(java.util.Map<String, java.nio.file.Path> paths) {
    this.functionSourcePaths = paths;
    return this;
  }

  /**
   * Opt in to cross-file {@code public static final} resolution against a source tree.
   *
   * <p><b>Why this is explicit rather than a side effect of {@link #withFunctionSourcePaths}.</b>
   * The resolver reads {@code .java} files from disk. A consumer that loads IR from a file on a
   * machine with no source must not have the analyser quietly walk whatever tree the IR's nominal
   * path happens to sit in. Two things went wrong when this was implicit:
   *
   * <ul>
   *   <li><b>It broke the contract.</b> The source-less consumer was reading source files off the
   *       filesystem, which is precisely what "the analysis depends only on the IR" forbids. A
   *       consumer on a different machine, or with the source deleted, silently lost the
   *       resolution.
   *   <li><b>It was unbounded.</b> The root was picked by walking up from the sample file, so a
   *       file in a temporary directory resolved to the whole temporary tree. Tens of thousands of
   *       files were read and regex-scanned, once per analysis.
   * </ul>
   *
   * <p>The resolution itself is still useful -- it is what lets a {@code Level.WARNING} or {@code
   * IO.STATIC_FIVE} FIELDLOAD attach a real parent instead of the orphan-SOURCE shape. So it stays,
   * but the caller now says where the source is, and the walk is capped by {@link
   * org.plumb.dfa.resolve.SourcePathResolver#MAX_INDEXED_FILES}.
   *
   * @param root the directory to index; {@code null} disables cross-file resolution
   * @return this engine
   */
  public DfaEngine withStaticFieldResolverRoot(java.nio.file.Path root) {
    if (root == null) {
      this.staticFieldResolver = null;
      return this;
    }
    try {
      SourcePathResolver spr = new SourcePathResolver(root);
      spr.index();
      StaticFieldResolver sfr = new StaticFieldResolver(spr);
      sfr.index();
      this.staticFieldResolver = sfr;
    } catch (Exception ignore) {
      // Leave staticFieldResolver null; FIELDLOADs fall through to the
      // orphan-SOURCE shape (pre- behaviour). The exception is
      // swallowed because resolver failure is non-fatal: every
      // FIELDLOAD just gets a parent-less SOURCE node and the
      // analysis continues.
    }
    return this;
  }

  /**
   * Run the analysis. Delegates to {@link #runParallel(TacProgram, int)} when {@code parallelism >
   * 0}, otherwise to {@code runSequential}.
   */
  public FindingReporter run(TacProgram program) {
    if (parallelism > 0) {
      return runParallel(program, parallelism);
    }
    return runSequential(program);
  }

  /**
   * Cross-TU analysis entry point. Builds a {@link org.plumb.dfa.cg.GlobalTACStore} from the given
   * per-TU programs, uses the {@link org.plumb.dfa.cg.GlobalCallGraphBuilder} to construct a
   * cross-TU call graph (signature-lookup, not type- hierarchy), and runs the same lifecycle as
   * {@link #runSequential(TacProgram)} on the merged view.
   *
   * <p>This is the <b>default</b> path for production scans: the CLI's {@code --cg-strategy}
   * defaults to {@code "global"} so every {@code sast-scan} invocation goes through this method
   * (and only falls back to {@code --cg-strategy cha}/{@code rta}/ {@code vta}/{@code 1cfa}/{@code
   * stub} if the user explicitly asks for a TU-local strategy). The merged program view keeps the
   * {@link org.plumb.tac.ir.MergedProgram} bookkeeping so findings still get a {@code sourcePath}
   * and per-enclosing- class file path mapping.
   *
   * <p>Sequential when {@code threads == 0}; parallel otherwise (mirrors {@link
   * #runParallel(TacProgram, int)}'s worklist scheduler).
   *
   * @param programs the per-TU {@link TacProgram}s collected by {@code ScanRunner.scanMerged}; must
   *     be non-empty. The store is built and frozen internally; the caller may continue to use the
   *     list afterwards.
   * @param threads {@code 0} for sequential, {@code >0} for parallel.
   */
  public FindingReporter runWithGlobalStore(java.util.List<TacProgram> programs, int threads) {
    Objects.requireNonNull(programs, "programs");
    if (programs.isEmpty()) {
      throw new IllegalArgumentException("runWithGlobalStore requires at least one program");
    }
    long t0 = System.currentTimeMillis();
    // 1. Freeze a GlobalTACStore from the per-TU programs.
    org.plumb.dfa.cg.GlobalTACStore store = org.plumb.dfa.cg.GlobalTACStore.of(programs);
    // 2. Build a merged view so the engine can iterate every
    //    function in a single TacProgram.
    org.plumb.tac.ir.MergedProgram merged = new org.plumb.tac.ir.MergedProgram(programs);
    TacProgram programView = merged.asProgram();
    // 3. Build the cross-TU call graph via the global strategy.
    //    GlobalCallGraphBuilder reads every function from the
    //    store; we feed it the merged view only for the
    //    (rare) log/audit path.
    org.plumb.dfa.cg.CallGraph cg = new org.plumb.dfa.cg.GlobalCallGraphBuilder(store).build();
    // 4. Run the standard lifecycle. We delegate to
    //    runParallel / runSequential's internal helpers via a
    //    synthetic "name=global" call into CallGraphStrategyFactory
    //    is not possible (global is cross-TU), so we hand-roll
    //    the worklist here. The duplication is intentional and
    //    documented; refactor when a 3rd entry point appears.
    FindingReporter reporter = new FindingReporter();
    this.budget = new AnalysisBudget(config.tuning());
    this.orderCache = new FunctionOrderCache();
    AnalysisContext ctx =
        new AnalysisContext(programView, cg, config, reporter, null, null, this.budget);
    ctx.setStaticFieldResolver(staticFieldResolver);
    reporter.setContext(ctx);
    reporter.setModuleScope(moduleScope());
    // Build a per-enclosing-class source-path map from the merged
    // programs so findings can locate the original .java file. The
    // synthetic MergedProgram.asProgram() has a null sourcePath, so
    // the engine's own fallback would leave the path as "unknown"
    // without this map.
    java.util.Map<String, java.nio.file.Path> crossTuSourcePaths =
        functionSourcePaths == null
            ? new java.util.HashMap<>()
            : new java.util.HashMap<>(functionSourcePaths);
    for (TacProgram src : programs) {
      java.nio.file.Path sp = src.sourcePath();
      if (sp == null) continue;
      for (TacFunction fn : src.functions()) {
        String enc = fn.enclosingClass();
        if (enc != null && !enc.isEmpty()) {
          crossTuSourcePaths.putIfAbsent(enc, sp);
        }
        // The SIGNATURE is registered as well, because a TOP-LEVEL function has no
        // enclosing class: a Kotlin or Scala file-level function, or a Java file's
        // package-private helper declared outside any class. Registering only by
        // enclosing class leaves those functions unresolved, and a finding in one is
        // reported as file="unknown".
        String sig = fn.signature();
        if (sig != null && !sig.isEmpty()) {
          crossTuSourcePaths.putIfAbsent(sig, sp);
        }
      }
    }
    registerSourcePaths(ctx, programView, crossTuSourcePaths);
    // initEngine
    for (DfaChecker c : checkers) {
      try {
        c.initEngine(ctx);
      } catch (RuntimeException ex) {
        LOG.warn("Checker {} initEngine failed: {}", c.name(), ex.toString());
      }
    }
    // Wire the solver bridge (from any TaintedCheckerBase) into the
    // cross-checker guard store so the guard tracking sees Z3 results.
    ctx.guardStore().setBridge(boundedSolverBridge(ctx));
    // Pre-pass: warm up the MethodSummary cache so every checker's
    // first checkInstruction sees a pre-populated summary for any
    // function it may call.
    warmUpMethodSummaries(ctx, programView);
    computeParamOrigins(ctx, programView, cg);
    solvePointsTo(ctx, programView);
    // worklist
    int fnCount = 0;
    int summaryCount = 0;
    Set<SourceOrigin> analysedOrigins = config.tuning().analysedOrigins();
    if (threads > 0) {
      // parallel
      runWorklistGlobal(ctx, programView, cg, threads, store, analysedOrigins);
      fnCount = ctx.factBag().operands().size();
      summaryCount = ctx.summaryCache().size();
    } else {
      // sequential
      for (TacFunction fn : orderedFunctions(programView, cg)) {
        fnCount++;
        if (!analysedOrigins.contains(fn.origin())) continue;
        ctx.factBag().clear();
        ctx.sideEffectConsumer().clear();
        analyseOneFunction(ctx, fn);
        FunctionSummary merged2 = summariseOneFunction(ctx, fn);
        if (!merged2.isTrivial() || merged2.hasBody()) {
          ctx.summaryCache().put(fn.signature(), merged2);
          summaryCount++;
        }
        // qualified-alias put. Juliet's cross-TU
        // scan goes through {@code runWithGlobalStore}
        // (this code path). Without this helper call the
        // bare {@code method/arity} alias collides across
        // the 60+ Variant 42/45 files that share
        // {@code badSource/0} and last-write-wins returns
        // the wrong Variant's summary.
        r716putQualifiedAliasSummary(ctx, fn, merged2);
      }
    }
    // drain any functions that need re-analysis because
    // a FIELDSTORE populated crossFnClassFieldTaint for a
    // field they had already read (their first walk ran when
    // the map was empty). The checkers track the queue; we
    // re-run them here so the FIELDLOAD picks up the now-
    // populated taint.
    org.plumb.dfa.taint.TaintedCheckerBase.drainRerunFns(this::rerunOneFn, ctx);
    // finish
    for (DfaChecker c : checkers) {
      try {
        c.finish(ctx);
      } catch (RuntimeException ex) {
        LOG.warn("Checker {} finish failed: {}", c.name(), ex.toString());
      }
    }
    long ms = System.currentTimeMillis() - t0;
    LOG.info(
        "DfaEngine run (global, {} workers): {} functions "
            + "analysed, {} summarised, {} findings, {} ms",
        Math.max(1, threads),
        fnCount,
        summaryCount,
        reporter.size(),
        ms);
    logBudgetReport(ctx);
    return reporter;
  }

  /**
   * The order cache for the current run.
   *
   * <p>Falls back to creating one when the engine was used without one -- a context built by hand,
   * or a run that has not started yet. The fallback keeps the code path working rather than
   * failing, and it is correct (just slower) because the cache is a pure optimisation.
   */
  private FunctionOrderCache orderCacheFor(AnalysisContext ctx) {
    FunctionOrderCache c = this.orderCache;
    if (c == null) {
      c = new FunctionOrderCache();
      this.orderCache = c;
    }
    return c;
  }

  /**
   * Logs the budget's exhaustion record at the end of a run.
   *
   * <p>The record is logged at WARN when the analysis was incomplete, so it is visible in the build
   * output without being a finding. An incomplete analysis and a complete one produce the same
   * findings so far as the user can tell, so this line is the only signal that something was
   * skipped.
   */
  private void logBudgetReport(AnalysisContext ctx) {
    AnalysisBudget b = budget;
    if (b == null) return;
    // Fold the solver's own timeout counter into the budget record. The solver bridge is
    // bounded by z3_timeout_ms, so a query that timed out is a budget exhaustion that
    // happened inside the bridge rather than in the engine -- but it is the same kind of
    // event and must be visible in the same report.
    org.plumb.dfa.solver.TimeBoundedSolverBridge solver = this.boundedSolver;
    if (solver != null && solver.timeouts() > 0) {
      b.record(
          AnalysisBudget.Dimension.SOLVER_TIME,
          solver.timeoutMs(),
          solver.timeouts() + " solver quer" + (solver.timeouts() == 1 ? "y" : "ies"));
    }
    // The same for the summary cache: a refused summary means a function's cross-function
    // facts did not reach its callers, so the analysis was incomplete. The cache's own
    // capacity is the limit that was reached.
    if (ctx != null && ctx.summaryCache() != null) {
      long overflow = ctx.summaryCache().overflowCount();
      if (overflow > 0) {
        b.record(
            AnalysisBudget.Dimension.SUMMARY_CACHE_SIZE,
            ctx.summaryCache().capacity(),
            overflow + " summar" + (overflow == 1 ? "y" : "ies") + " refused (cache full)");
      }
      // The cache's hit/miss ratio is NOT a budget exhaustion -- it is the cost of the
      // cross-function analysis, and it is the number that says whether the cache is
      // earning its keep. A cache that never hits means every callee is being
      // re-analysed, which is slow; a cache that always hits means the program has
      // few distinct callees. Neither is an error, so this is LOGGED rather than
      // RECORDED as a budget event.
      //
      // The counters have existed since the cache was written, and the cache's own
      // javadoc claimed "the post-run log reads these via hits() / misses()". No
      // call site existed. A counter nobody reads is a claim nobody can check, so
      // the log now carries the number.
      logSummaryCacheStats(ctx.summaryCache());
    }
    AnalysisBudget.BudgetReport r = b.report();
    if (r.complete()) {
      LOG.info("Analysis budget: complete within all limits");
    } else {
      LOG.warn("Analysis budget: {}", r.summary());
    }
  }

  /**
   * Log the summary cache's hit/miss ratio for this run.
   *
   * <p>The ratio is the cheapest measure of whether the cross-function analysis is paying for
   * itself, and it is the number an operator asks for first when a scan is slower than expected.
   *
   * @param cache the cache this run used
   */
  private static void logSummaryCacheStats(org.plumb.dfa.summary.FunctionSummaryCache cache) {
    if (cache == null) return;
    long hits = cache.hits();
    long misses = cache.misses();
    long total = hits + misses;
    if (total == 0) {
      // Nothing was looked up, so a ratio would be "0/0" -- a division by zero in
      // the log line. Say what is true instead: the cache was not used.
      LOG.info("Summary cache: not used (0 lookups)");
      return;
    }
    int percent = (int) Math.round(100.0 * hits / total);
    LOG.info(
        "Summary cache: {} hits, {} misses ({}% hit rate), {} entries, capacity {}",
        hits, misses, percent, cache.size(), cache.capacity() > 0 ? cache.capacity() : "unbounded");
  }

  /**
   * The solver bridge for this run, wrapped so each query is time-bounded.
   *
   * <p>Finds the first checker that offers a solver bridge and wraps it in a {@link
   * TimeBoundedSolverBridge} carrying the configured {@code z3_timeout_ms}. The wrapper is created
   * once per run and reused, so its timeout counter is per-run and can be reported.
   *
   * @return the bounded bridge, or {@code null} if no checker offers a solver
   */
  private org.plumb.dfa.solver.SolverZ3Bridge boundedSolverBridge(AnalysisContext ctx) {
    for (DfaChecker c : this.checkers) {
      if (!(c instanceof org.plumb.dfa.taint.TaintedCheckerBase tcb)) continue;
      if (tcb.solverBridge() == null) continue;
      long timeoutMs = this.config.tuning().z3TimeoutMs();
      if (timeoutMs <= 0) {
        return tcb.solverBridge();
      }
      org.plumb.dfa.solver.TimeBoundedSolverBridge bounded =
          new org.plumb.dfa.solver.TimeBoundedSolverBridge(tcb.solverBridge(), timeoutMs);
      this.boundedSolver = bounded;
      return bounded;
    }
    return null;
  }

  /** The time-bounded solver bridge for the current run, or {@code null} if none was created. */
  public org.plumb.dfa.solver.TimeBoundedSolverBridge boundedSolverBridgeForTest() {
    return this.boundedSolver;
  }

  /**
   * Parallel-worklist variant of {@link #runWithGlobalStore}. Same dep-count algorithm as {@link
   * #runWorklist(AnalysisContext, TacProgram, CallGraph, int)} but reads the dependency map from
   * the supplied {@link org.plumb.dfa.cg.GlobalTACStore} so the cross-TU callee view is used
   * directly. The per-TU program view is only used for the merged function iteration order.
   */
  private void runWorklistGlobal(
      AnalysisContext ctx,
      TacProgram programView,
      CallGraph cg,
      int threads,
      org.plumb.dfa.cg.GlobalTACStore store,
      Set<SourceOrigin> analysedOrigins) {
    // The unit of work is the MERGED KEY, not the bare signature.
    //
    // The merged view is keyed by "<enclosingClass> :: <signature>" (see
    // MergedProgram.mergedKey), and two functions can share a signature: two subclasses of an
    // abstract base both implementing action(String). Keying the worklist by the bare signature
    // collapsed those two into one work item -- one of them was never analysed -- and looking a
    // function up by the bare signature did not find it at all, because the merged stub is keyed
    // by the merged key. That is why the parallel path used to report "6 of 6 functions
    // processed" and "0 functions analysed": every lookup returned null.
    java.util.Map<String, TacFunction> byMergedKey = new java.util.LinkedHashMap<>();
    for (TacFunction fn : programView.functions()) {
      byMergedKey.put(org.plumb.tac.ir.MergedProgram.mergedKey(fn), fn);
    }
    // Build the dep map from the cross-TU call graph. The
    // standard CallGraph already has forward+reverse maps;
    // we just need to count "in-TU callees" per function.
    java.util.concurrent.ConcurrentMap<String, AtomicInteger> deps =
        new java.util.concurrent.ConcurrentHashMap<>();
    for (var entry : byMergedKey.entrySet()) {
      TacFunction fn = entry.getValue();
      if (!analysedOrigins.contains(fn.origin())) continue;
      org.plumb.dfa.cg.FunctionNode node = new org.plumb.dfa.cg.FunctionNode(fn.signature());
      int calleeCount = 0;
      for (var cs : cg.callsFrom(node)) {
        String calleeSig = cs.callee().signature();
        if (calleeSig.equals(fn.signature())) continue;
        // Count the callee only if it's in any TU we know
        // about (via the global store). Library calls
        // don't count.
        if (store.functionBySignature(calleeSig).isPresent()) {
          calleeCount++;
        }
      }
      deps.put(entry.getKey(), new AtomicInteger(calleeCount));
    }
    // Reverse caller map. Keyed by the CALLEE SIGNATURE, because that is what the call graph
    // resolves to; the values are the callers' merged keys.
    java.util.Map<String, java.util.List<String>> callersOf = new java.util.HashMap<>();
    // How many merged keys share each signature. A caller must wait for ALL of them: the
    // summary of a signature is what the caller reads, and the last writer wins, so letting a
    // caller proceed while a sibling variant is still unanalysed would make the result depend on
    // the scheduling.
    java.util.Map<String, AtomicInteger> variantsOfSignature = new java.util.HashMap<>();
    for (var entry : byMergedKey.entrySet()) {
      TacFunction fn = entry.getValue();
      if (!analysedOrigins.contains(fn.origin())) continue;
      variantsOfSignature
          .computeIfAbsent(fn.signature(), k -> new AtomicInteger())
          .incrementAndGet();
      org.plumb.dfa.cg.FunctionNode node = new org.plumb.dfa.cg.FunctionNode(fn.signature());
      for (var cs : cg.callsFrom(node)) {
        String calleeSig = cs.callee().signature();
        if (calleeSig.equals(fn.signature())) continue;
        if (store.functionBySignature(calleeSig).isEmpty()) continue;
        callersOf.computeIfAbsent(calleeSig, k -> new java.util.ArrayList<>()).add(entry.getKey());
      }
    }
    // Initial ready queue: leaves (deps == 0)
    java.util.concurrent.ConcurrentLinkedQueue<String> readyQ =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    for (var e : deps.entrySet()) {
      if (e.getValue().get() == 0) readyQ.add(e.getKey());
    }
    // Spin up worker pool : same pattern as the standard
    // runWorklist.
    AtomicInteger processed = new AtomicInteger(0);
    int total = deps.size();
    java.util.concurrent.ExecutorService exec =
        java.util.concurrent.Executors.newFixedThreadPool(
            threads, namedThreadFactory("plumb-dfa-global"));
    java.util.concurrent.Phaser phaser = new java.util.concurrent.Phaser(1);
    // Per-worker checkers. A checker holds per-function state, so a shared instance would
    // have one worker's initFunction clear another worker's facts mid-walk -- the run would
    // LOSE findings, not gain them. Each worker THREAD therefore builds its own set, once,
    // and reuses it for every function that thread analyses.
    ThreadLocal<List<DfaChecker>> workerCheckers =
        ThreadLocal.withInitial(
            () -> {
              List<DfaChecker> cs = checkersForWorker();
              for (DfaChecker c : cs) {
                try {
                  c.initEngine(ctx);
                } catch (RuntimeException ex) {
                  LOG.warn("Checker {} initEngine failed: {}", c.name(), ex.toString());
                }
              }
              return cs;
            });
    try {
      while (true) {
        String polled;
        while ((polled = readyQ.poll()) != null) {
          final String sig = polled;
          phaser.register();
          exec.submit(
              () -> {
                try {
                  TacFunction fn = byMergedKey.get(sig);
                  if (fn != null && analysedOrigins.contains(fn.origin())) {
                    List<DfaChecker> mine = workerCheckers.get();
                    ctx.factBag().clear();
                    ctx.sideEffectConsumer().clear();
                    analyseOneFunction(ctx, fn, mine);
                    FunctionSummary m = summariseOneFunction(ctx, fn, mine);
                    if (!m.isTrivial() || m.hasBody()) {
                      // The summary is stored under the SIGNATURE, because that is what a call
                      // site resolves to. When two functions share a signature, the summary is
                      // merged with the one already there rather than overwriting it: an
                      // overwrite would make the result depend on which variant the scheduler
                      // happened to finish last.
                      ctx.summaryCache().merge(fn.signature(), m);
                    }
                    // qualified-alias put
                    // for the parallel global worklist.
                    // ConcurrentHashMap.put is
                    // last-writer-wins per key, but
                    // different functions in different
                    // files have different enclosing
                    // classes and thus different
                    // qualified keys : no race.
                    r716putQualifiedAliasSummary(ctx, fn, m);
                  }
                  // The callers wait for EVERY variant that shares this signature.
                  AtomicInteger variantsLeft =
                      variantsOfSignature.get(fn == null ? sig : fn.signature());
                  boolean lastVariant = variantsLeft == null || variantsLeft.decrementAndGet() == 0;
                  if (!lastVariant) {
                    return;
                  }
                  java.util.List<String> cs = callersOf.get(fn == null ? sig : fn.signature());
                  if (cs != null) {
                    for (String callerSig : cs) {
                      AtomicInteger c = deps.get(callerSig);
                      if (c != null && c.decrementAndGet() == 0) {
                        readyQ.add(callerSig);
                      }
                    }
                  }
                } catch (RuntimeException ex) {
                  LOG.warn("Global analysis of {} failed: {}", sig, ex.toString());
                } finally {
                  processed.incrementAndGet();
                  phaser.arriveAndDeregister();
                }
              });
        }
        if (phaser.getRegisteredParties() == 1) break;
        phaser.arriveAndAwaitAdvance();
      }
    } finally {
      exec.shutdown();
      try {
        if (!exec.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
          exec.shutdownNow();
        }
      } catch (InterruptedException ie) {
        exec.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    LOG.info("DfaEngine global worklist: {} of {} functions processed", processed.get(), total);
  }

  /**
   * Pre-pass to warm the {@link org.plumb.dfa.summary.MethodSummaryCache} for every function in the
   * program.
   *
   * <p>Without this pre-pass, each function's {@link org.plumb.dfa.summary.MethodSummary} is built
   * <i>after</i> its analysis + summary step. The fold consumers consult the cache via {@link
   * org.plumb.dfa.summary.SummaryApplier} during {@code checkInstruction}, so if the RPO visits a
   * caller before its callee (which happens whenever the CallGraph's node iteration order is not
   * strict callee-before-caller : e.g. merged test corpora where entry points and their library
   * helpers share the same RPO frontier), the cache is cold for the first iteration and the fold
   * consumers see {@code UNKNOWN} instead of {@code const:true}.
   *
   * <p>MethodSummary building is a single linear pass over the function's IR : it doesn't depend on
   * any analysis state, so we can do it as a pre-pass before any analysis. The pre-pass guarantees
   * the cache is fully warm when the first analysis iteration begins. The parallel worklist
   * scheduler also benefits because the parallel workers see a fully-warm cache from the start.
   */
  public int warmUpMethodSummaries(AnalysisContext ctx, TacProgram program) {
    Objects.requireNonNull(ctx, "ctx");
    Objects.requireNonNull(program, "program");
    Set<SourceOrigin> analysedOrigins = config.tuning().analysedOrigins();
    int built = 0;
    for (TacFunction fn : program.functions()) {
      if (!analysedOrigins.contains(fn.origin())) continue;
      try {
        org.plumb.dfa.cg.FunctionSignature sig = parseFnSignature(fn);
        ctx.methodSummaryCache().put(sig, fn);
        built++;
      } catch (RuntimeException ex) {
        LOG.warn("MethodSummary pre-warm failed for {}: {}", fn.signature(), ex.toString());
      }
    }
    LOG.info(
        "MethodSummary pre-pass: warmed cache for " + "{} of {} functions",
        built,
        program.functions().size());
    return built;
  }

  /**
   * compute, for every function and parameter index, the taint ORIGIN its callers pass in, and
   * publish it on {@link AnalysisContext#setParamOrigins(java.util.Map)}.
   *
   * <p>Why this has to be a pre-pass: functions are analysed in reverse post-order, so a callee is
   * analysed before ANY of its callers. When a sink inside the callee emits a finding, the caller's
   * {@link FunctionSummary} does not exist yet — so the callee can only report its own
   * entry-boundary {@code param:<name>} label. That label is indistinguishable in the output from
   * "taint never crossed the call boundary", which is what made cross-file taint look absent.
   *
   * <p>The map is built by propagating an origin label forward over the call graph until it reaches
   * a fixpoint (the chain depth is bounded by the number of functions, so this terminates):
   *
   * <ol>
   *   <li>Entry points and any parameter not written by a caller start as their own {@code
   *       param:<name>} label — that is genuine provenance for a real trust boundary.
   *   <li>When a caller's param {@code i} has a recovered origin, the callee's matching param is
   *       assigned that same origin.
   *   <li>A parameter reached by two callers with DIFFERENT origins is ambiguous; we clear it so
   *       the finding falls back to the honest {@code param:<name>} label instead of guessing.
   * </ol>
   *
   * <p>Real input sources ({@code request.getParameter}, {@code argv}, ...) win outright: the
   * pre-pass seeds them from {@link org.plumb.dfa.taint.InputSourcePatterns} so the reported origin
   * is the actual untrusted entry point rather than an intermediate parameter name.
   */
  /**
   * solve the program's alias map once, before any checker runs.
   *
   * <p>Points-to is a whole-program property: a fact learned in one function routinely changes the
   * answer in another, so a per-function solve would be both wrong and more expensive. Solving once
   * up front means every checker reads the same fixed point, which is the point of putting it on
   * the {@link AnalysisContext} rather than inside one rule.
   *
   * <p>Failures are contained. The solver is a static analysis like any other, and a source tree it
   * cannot handle (an unsupported construct, a namespace it cannot reconcile) must degrade to "no
   * alias information" rather than take the scan down with it -- every consumer treats an absent
   * answer as "unknown", never as "aliases".
   */
  private void solvePointsTo(AnalysisContext ctx, TacProgram program) {
    try {
      // the policy is a configuration knob, not a hard-coded choice. The default is
      // CALL_SITE (1-CFA) because context insensitivity merges two invocations of the same
      // method into one formal and one return set, and every consumer of this map inherits
      // that merge -- see PointsToSolver's class comment.
      org.plumb.dfa.points.AbstractObject.Policy policy = config.tuning().effectivePointsToPolicy();
      org.plumb.dfa.points.PointsToSolver solver = new org.plumb.dfa.points.PointsToSolver(policy);
      solver.solve(program.functions());
      ctx.setPointsToSolver(solver);
      // copy equivalence is solved in the same pass so a checker can transfer a fact from
      // one name to another even when the receiver has no points-to set (a parameter, typically).
      ctx.setCopyEquivalence(org.plumb.dfa.points.CopyEquivalence.of(program.functions()));
      LOG.debug("points-to analysis solved under policy {}", policy);
    } catch (RuntimeException | StackOverflowError e) {
      // Deliberately broad: the alias map is an optimisation for precision, never a correctness
      // prerequisite, so any failure here is downgraded to "no alias information available".
      ctx.setPointsToSolver(null);
      ctx.setCopyEquivalence(null);
      LOG.debug("points-to analysis unavailable, continuing without alias information", e);
    }
  }

  private void computeParamOrigins(AnalysisContext ctx, TacProgram program, CallGraph cg) {
    // key = "signature#paramIndex" -> origin label for that parameter.
    // returnKey(fn) holds the origin of the function's return value.
    HashMap<String, String> origin = new HashMap<>();
    HashSet<String> ambiguous = new HashSet<>();
    // parameters every observed call site supplies with a value the analysis can
    // positively prove clean. See setParamCleanliness for the contract.
    HashSet<String> cleanArgs = new HashSet<>();
    HashSet<String> dirtyArgs = new HashSet<>();

    // The function list, taken ONCE.
    //
    // `program.functions()` copies the whole function map into a fresh list on
    // every call, and this method's round bound is derived from the function
    // count. On a program with tens of thousands of functions that is tens of
    // thousands of rounds each copying tens of thousands of entries. The scan
    // did not get slow; it stopped finishing.
    //
    // The view is stable for the whole method: the program is not mutated
    // while the origins are computed.
    java.util.Collection<org.plumb.tac.ir.TacFunction> fns = program.functionView();

    // --- pass 1: intra-procedural label flow, per function -------------------
    // localLabel[fn][varName] is the origin that var currently carries inside fn.
    //
    // THE KEY IS THE MERGED KEY (enclosing class + signature), NOT THE
    // SIGNATURE ALONE.
    //
    // A corpus of many files that each declare the same method name makes
    // the SIGNATURE ambiguous: thousands of functions share one signature,
    // and keyed by the signature alone they all shared ONE label map, so
    // each function overwrote the others' labels.
    //
    // That is not only wrong, it never terminates: the fixpoint's "did
    // anything change?" flag was set on every round forever, so the loop
    // ran every round it was allowed -- each one redoing the same work. The
    // scan did not get slow; it stopped finishing.
    HashMap<String, HashMap<String, String>> localLabel = new HashMap<>();

    // The labels of the SHARED CELLS -- a storage location that more than one
    // function reads and writes. Keyed by "<receiver>.<field>", the same shape the
    // main analysis uses for its cross-function field tracker.
    //
    // <p><b>Why the origin pre-pass needs them.</b> The pre-pass computes, for
    // every parameter, the origin its CALLERS pass in. It does that with a purely
    // local label flow over each function's instructions, seeded from that
    // function's own parameters.
    //
    // <p>A value that reaches a sink through a shared cell does not fit that shape:
    // the closure's own parameter list is EMPTY (the captured variable is not a
    // parameter -- it is a free identifier in the closure's body), so the pre-pass
    // had no label for it, and the sink's parameter kept the entry-boundary
    // {@code param:<name>} label. The taint had crossed the boundary; the report
    // could not say so.
    //
    // <p>So the cell's label is a whole-program fact, computed in the same
    // fixpoint: a write to the cell publishes a label, and a read of the cell picks
    // it up.
    HashMap<String, String> cellLabel = new HashMap<>();

    for (TacFunction fn : fns) {
      HashMap<String, String> lab = new HashMap<>();
      // Entry boundary: every parameter is a trust boundary in its own right.
      //
      // A parameter bound by a FRAMEWORK entry point (@RequestParam, @PathVariable,
      // ...) is labelled with the framework qualifier as well, so the origin that
      // reaches a caller names the trust boundary the value came from.
      for (int i = 0; i < fn.params().size(); i++) {
        String name = fn.params().get(i).name();
        String label = FrameworkEntryPoints.sourceLabelFor(fn, name);
        if (label == null) {
          label = "param:" + name;
        }
        lab.put(name, label);
        origin.put(key(fn, i), label);
      }
      localLabel.put(mergedKeyOf(fn), lab);
    }

    // Iterate: local flow within a function, plus transfer across call edges, to a
    // fixpoint. Bounded so it provably terminates.
    // Index program functions by bare "method/arity" so a CALL's calleeSig can be
    // resolved without depending on the call graph's namespace.
    //
    // The index is ALSO keyed by the enclosing class, so the disambiguation
    // scan is O(1) instead of O(candidates).
    //
    // WHY THAT MATTERS. A corpus of many files that each declare a method
    // with the same name and arity puts THOUSANDS of functions in one bare
    // `name/arity` bucket. Each call site then scanned that whole bucket,
    // and the scan never reached the analysis.
    //
    // The semantics are unchanged. The old code picked the single candidate
    // whose enclosing class equals the call's qualifier, and returned null
    // when two candidates matched (ambiguous). The class-keyed index answers
    // the same question by looking up only that class's candidates.
    java.util.Map<String, List<TacFunction>> byBare = new java.util.HashMap<>();
    java.util.Map<String, List<TacFunction>> byBareAndClass = new java.util.HashMap<>();
    for (TacFunction fn : fns) {
      String bare = nameArity(fn.signature());
      byBare.computeIfAbsent(bare, k -> new ArrayList<>()).add(fn);
      String enc = fn.enclosingClass();
      if (enc != null && !enc.isEmpty()) {
        byBareAndClass.computeIfAbsent(bare + " :: " + enc, k -> new ArrayList<>()).add(fn);
      }
    }

    int maxRounds = Math.max(4, fns.size() + 2);
    HashMap<String, String> retLabel = new HashMap<>();

    for (int round = 0; round < maxRounds; round++) {
      boolean changed = false;

      // (a) intra-procedural propagation over each function's instructions
      for (TacFunction fn : fns) {
        HashMap<String, String> lab = localLabel.get(mergedKeyOf(fn));
        if (lab == null) continue;
        for (TacInstruction ins : fn.instructions()) {
          String destName = destVarName(ins);
          if (destName == null) continue;

          switch (ins.kind) {
            case CALL -> {
              // A call's dest inherits from a tainted ARGUMENT (receiver
              // passthrough) or from a taint source callee.
              String from = labelOfAnyArg(ins, lab);
              if (from == null
                  && org.plumb.dfa.taint.InputSourcePatterns.defaults()
                      .matches(ins.calleeSig, ctx.config().sinkFamilies(), fn.sourceLanguage())) {
                from = "input:" + ins.calleeSig;
              }
              if (from != null && !from.equals(lab.get(destName))) {
                lab.put(destName, from);
                changed = true;
              }
              break;
            }
            case RETURN -> {
              String from = labelOfAnyArg(ins, lab);
              if (from != null && !from.equals(retLabel.get(fn.signature()))) {
                retLabel.put(fn.signature(), from);
                changed = true;
              }
              break;
            }
            case FIELDSTORE -> {
              // A write to a SHARED CELL publishes the written value's label to
              // the cell, so a function that reads the cell sees it. The cell is
              // keyed by "<receiver>.<field>", the same shape the main analysis
              // uses for its cross-function field tracker.
              String cellKey = sharedCellKey(ins, fn);
              if (cellKey != null) {
                // The STORED value of a FIELDSTORE is its DEST, not one of the
                // operand slots: the TAC says "lhs1.fieldName = dest". So the
                // value's label is read from dest, and labelOfAnyArg -- which
                // looks at args/lhs1/lhs2 -- would find nothing.
                String from = labelOf(ins.dest, lab);
                if (from == null) {
                  from = labelOfAnyArg(ins, lab);
                }
                if (from != null && !from.equals(cellLabel.get(cellKey))) {
                  cellLabel.put(cellKey, from);
                  changed = true;
                }
              }
              break;
            }
            case FIELDLOAD -> {
              // A read of a SHARED CELL takes the cell's label. Without this the
              // closure's read of the captured variable had no label, and the
              // value it passed to the sink had no origin either.
              String cellKey = sharedCellKey(ins, fn);
              if (cellKey != null) {
                String from = cellLabel.get(cellKey);
                if (from != null && !from.equals(lab.get(destName))) {
                  lab.put(destName, from);
                  changed = true;
                }
              }
              break;
            }
            default -> {
              // Any instruction whose dest derives from an operand inherits
              // that operand's label. Covers MOVE, BINOP, FIELDLOAD/STORE,
              // ARRAYLOAD/STORE, NEW, casts, and the StringBuilder family.
              String from = labelOfAnyArg(ins, lab);
              if (from != null && !from.equals(lab.get(destName))) {
                lab.put(destName, from);
                changed = true;
              }
            }
          }
        }
      }

      // (b) cross-function transfer.
      //
      // edges come from the TAC CALL instructions, NOT from the call graph.
      // The global call graph namespaces a node as "Class :: signature" while the
      // merged TacProgram uses the bare signature, so matching them by equality
      // silently yields zero edges -- which is exactly the bug this replaced.
      for (TacFunction caller : fns) {
        HashMap<String, String> lab = localLabel.get(mergedKeyOf(caller));
        if (lab == null) continue;
        for (TacInstruction call : caller.instructions()) {
          if (call.kind != TacInstruction.Kind.CALL) continue;
          TacFunction callee = resolveCallee(program, byBare, byBareAndClass, call.calleeSig);
          if (callee == null) continue;
          List<org.plumb.tac.ir.TacOperand> args = call.args;
          for (int j = 0; j < callee.params().size(); j++) {
            int argIdx = j + 1; // args[0] is the receiver slot
            if (argIdx >= args.size()) break;
            String k = key(callee, j);
            // was this argument a literal? Only a literal is positive proof
            // of cleanliness -- see the CLEAN_ARGUMENTS contract.
            //
            // This must be evaluated BEFORE the TacVar guard below: a literal is not a
            // TacVar, so placing it after means every literal argument has already been
            // skipped by `continue` and no parameter is ever recorded clean. That
            // ordering mistake is invisible in the output -- the map is simply empty
            // and the gate silently does nothing.
            if (!(args.get(argIdx) instanceof org.plumb.tac.ir.TacOperand.TacVar)) {
              cleanArgs.add(k);
              continue;
            }
            dirtyArgs.add(k);
            if (!(args.get(argIdx) instanceof org.plumb.tac.ir.TacOperand.TacVar av)) {
              continue;
            }
            String src = lab.get(av.name());
            if (src == null) continue;
            if (ambiguous.contains(k)) continue;
            String prev = origin.get(k);
            // the entry-boundary seed (`param:<own name>`) is a PLACEHOLDER,
            // not a real finding about provenance -- a callee always has one, which is
            // exactly why it used to shadow every propagated origin and leave the
            // reported source stuck at the callee's own parameter. So a propagated
            // origin always overwrites a placeholder, and only two genuinely
            // different propagated origins count as ambiguity.
            boolean prevIsSeed = prev != null && prev.equals(placeholderFor(callee, j));
            if (prev == null || prevIsSeed) {
              if (!src.equals(prev)) {
                origin.put(k, src);
                changed = true;
              }
            } else if (!prev.equals(src)) {
              ambiguous.add(k);
              origin.remove(k);
              changed = true;
            }
          }
        }
      }

      if (!changed) break;
    }

    for (String k : ambiguous) {
      origin.remove(k);
    }
    ctx.setParamOrigins(Map.copyOf(origin));
    // only parameters with at least one call site and no non-literal call site count
    // as clean. A parameter nobody calls keeps "unknown", which preserves the historical
    // behaviour where a method analysed as an entry point still treats its parameters as
    // trust boundaries.
    java.util.Map<String, Boolean> clean = new java.util.LinkedHashMap<>();
    for (String k : cleanArgs) {
      if (!dirtyArgs.contains(k)) clean.put(k, Boolean.TRUE);
    }
    ctx.setParamCleanliness(java.util.Collections.unmodifiableMap(clean));
    /* diag removed */
  }

  /** name of the variable an instruction writes, or {@code null}. */
  private static String destVarName(TacInstruction ins) {
    if (ins == null) return null;
    return (ins.dest instanceof org.plumb.tac.ir.TacOperand.TacVar d) ? d.name() : null;
  }

  /**
   * The key of the SHARED CELL a field access names, or {@code null} when the access is not a
   * shared cell.
   *
   * <p><b>What makes a field access a shared cell.</b> The receiver is a named local of the
   * function that is <i>never written</i> in it. A receiver the function writes is an object the
   * function owns, and its fields are that object's own business: two calls on two different
   * instances of the same class would otherwise be treated as one cell, and a value would leak
   * between them.
   *
   * <p>A receiver the function only READS is a cell some OTHER function also writes -- the cell
   * exists outside this function, so a write there and a read here are the same storage.
   *
   * <p>This is the shape a closure's captured variable has. The frontend lowers a capture into a
   * field of a synthetic holder whose name is the same in the caller and in the closure: the holder
   * is declared as a local and never assigned, and the captured variable is its field. So the
   * capture is a shared cell by this test -- and so is any other language's equivalent, which is
   * why the test is stated in terms of the receiver's origin rather than in terms of any one
   * language's convention.
   *
   * @param ins the field access
   * @param fn the function the access is in
   * @return the cell key, or {@code null}
   */
  private static String sharedCellKey(TacInstruction ins, TacFunction fn) {
    if (ins == null || ins.fieldName == null || ins.fieldName.isEmpty()) {
      return null;
    }
    if (!(ins.lhs1 instanceof org.plumb.tac.ir.TacOperand.TacVar recv)) {
      return null;
    }
    String name = recv.name();
    if (name == null || name.isEmpty() || "this".equals(name)) {
      return null;
    }
    // A temp is created by the function that uses it, so it is never a cell
    // another function writes.
    if (recv.isTemp()) {
      return null;
    }
    // A receiver the function WRITES is its own object, not a shared cell.
    if (writesLocal(fn, name)) {
      return null;
    }
    return name + "." + ins.fieldName;
  }

  /**
   * Whether a function writes to a named variable.
   *
   * <p>A write is an instruction whose DEST is the variable: a MOVE into it, a FIELDSTORE onto it,
   * or a call whose result lands in it.
   *
   * @param fn the function
   * @param name the variable's name
   * @return whether the function writes it
   */
  private static boolean writesLocal(TacFunction fn, String name) {
    if (fn == null || name == null) {
      return false;
    }
    for (TacInstruction ins : fn.instructions()) {
      if (ins.dest instanceof org.plumb.tac.ir.TacOperand.TacVar d && name.equals(d.name())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The label an operand carries in a function's label map, or {@code null}.
   *
   * @param op the operand
   * @param lab the function's label map
   * @return the label, or {@code null}
   */
  private static String labelOf(org.plumb.tac.ir.TacOperand op, HashMap<String, String> lab) {
    if (op == null || lab == null) {
      return null;
    }
    if (op instanceof org.plumb.tac.ir.TacOperand.TacVar v) {
      return lab.get(v.name());
    }
    return null;
  }

  /**
   * The first non-null label among the instruction's OPERANDS, or {@code null} when none of them
   * carries taint. Used for both dest-inherits-operand and RETURN.
   *
   * <p><b>Why the operand slots, and not only {@code args}.</b> The TAC keeps a call's arguments in
   * {@link TacInstruction#args}, but every OTHER opcode names its operands in the fixed slots
   * {@link TacInstruction#lhs1} and {@link TacInstruction#lhs2}. A {@code MOVE} -- which is how an
   * assignment is expressed -- has its source in {@code lhs1} and an EMPTY argument list. A {@code
   * BINOP}, a {@code FIELDLOAD} and an {@code ARRAYLOAD} are the same.
   *
   * <p>So scanning only {@code args} sees the operands of a CALL and of nothing else. The
   * consequence is silent and severe: a local assignment never inherits a label, so a value that
   * arrives at a sink through a local variable is invisible to the origin pre-pass, and the finding
   * falls back to the honest-but-useless {@code param:<name>} label of the parameter it was passed
   * as. The taint had crossed the call boundary; the report just could not say so.
   *
   * <p>The order matters and follows the TAC's own reading order: the arguments first (a call's
   * arguments are its inputs), then the left-hand operand, then the right-hand one.
   */
  private static String labelOfAnyArg(TacInstruction ins, HashMap<String, String> lab) {
    if (ins == null || lab == null) return null;
    for (org.plumb.tac.ir.TacOperand op : ins.args) {
      if (op instanceof org.plumb.tac.ir.TacOperand.TacVar v) {
        String l = lab.get(v.name());
        if (l != null) return l;
      }
    }
    if (ins.lhs1 instanceof org.plumb.tac.ir.TacOperand.TacVar v1) {
      String l = lab.get(v1.name());
      if (l != null) return l;
    }
    if (ins.lhs2 instanceof org.plumb.tac.ir.TacOperand.TacVar v2) {
      String l = lab.get(v2.name());
      if (l != null) return l;
    }
    return null;
  }

  /**
   * resolve a CALL's {@code calleeSig} (e.g. {@code "SourceLayer.readRequestParam/1"}) to the
   * function in the program whose declared signature is {@code "readRequestParam(String):String"}.
   * The two use different naming schemes, so we reduce both to {@code name/arity} and then
   * disambiguate.
   *
   * <p>When the {@code name/arity} key has more than one candidate (overloads, or the same method
   * name in different classes of a merged program) we prefer a candidate in the class named by the
   * call's qualifier, then a unique candidate. If neither is decisive we return {@code null}:
   * attributing a finding to an arbitrary overload would be worse than keeping the honest {@code
   * param:} label.
   */
  private static TacFunction resolveCallee(
      TacProgram program,
      java.util.Map<String, List<TacFunction>> byBare,
      java.util.Map<String, List<TacFunction>> byBareAndClass,
      String calleeSig) {
    if (calleeSig == null || byBare == null) return null;
    TacFunction exact = program.function(calleeSig);
    if (exact != null) return exact;
    String bare = nameArityOfCall(calleeSig);
    List<TacFunction> cands = byBare.get(bare);
    if (cands == null || cands.isEmpty()) return null;
    if (cands.size() == 1) return cands.get(0);
    String qualifier = qualifierOf(calleeSig);
    if (qualifier != null && !qualifier.isEmpty()) {
      // The candidates of THIS class, looked up directly. The old code
      // scanned every candidate in the bare bucket for one whose enclosing
      // class matched -- and the bucket holds thousands of same-named
      // methods in a corpus like Juliet's.
      //
      // The owner is matched by SIMPLE NAME as well as by the spelling the
      // call site used. The two spellings differ across languages: a Java
      // declaration's enclosing class is the short name ("JavaSink"), while a
      // Kotlin call site names it fully qualified ("demo.JavaSink") -- the
      // same class, two spellings. Without the simple-name fallback the lookup
      // missed, the parameter origin was never transferred, and the caller's
      // taint stopped at the language boundary.
      List<TacFunction> inClass =
          byBareAndClass == null ? null : byBareAndClass.get(bare + " :: " + qualifier);
      if (inClass != null && !inClass.isEmpty()) {
        if (inClass.size() > 1) return null; // ambiguous within the same class
        return inClass.get(0);
      }
      int lastDot = qualifier.lastIndexOf('.');
      if (lastDot >= 0) {
        String simpleOwner = qualifier.substring(lastDot + 1);
        List<TacFunction> bySimple =
            byBareAndClass == null ? null : byBareAndClass.get(bare + " :: " + simpleOwner);
        if (bySimple != null && !bySimple.isEmpty()) {
          if (bySimple.size() > 1) return null; // ambiguous
          return bySimple.get(0);
        }
        // The MODULE-CLASS spelling. A Scala call to a Java STATIC method names the
        // callee through the Java class's synthetic module class, whose name is the
        // class's name with a '$' appended: "demo.JavaSink$.run/1" for a method
        // declared in "JavaSink".
        //
        // Without this the lookup misses, the call resolves to no candidate, the
        // parameter origin is never transferred, and the caller's taint stops at the
        // language boundary -- while the scan still reports success.
        String withoutModuleSuffix = stripModuleClassSuffix(simpleOwner);
        if (!withoutModuleSuffix.equals(simpleOwner)) {
          List<TacFunction> byModule =
              byBareAndClass == null
                  ? null
                  : byBareAndClass.get(bare + " :: " + withoutModuleSuffix);
          if (byModule != null && !byModule.isEmpty()) {
            if (byModule.size() > 1) return null; // ambiguous
            return byModule.get(0);
          }
        }
      }
      return null;
    }
    return null;
  }

  /**
   * A name with the Scala module-class suffix removed.
   *
   * <p>A Java class's static members live in a synthetic MODULE CLASS whose name is the class's
   * name with a {@code $} appended, and that is the name a Scala call site uses. The suffix is
   * removed only when MATCHING: {@code Service$} and {@code Service} are two different classes, and
   * merging them would merge two real, distinct methods.
   *
   * @param name the name
   * @return the name without a trailing {@code $}
   */
  private static String stripModuleClassSuffix(String name) {
    if (name == null || name.length() < 2) return name;
    if (!name.endsWith("$")) return name;
    return name.substring(0, name.length() - 1);
  }

  /**
   * A key that identifies a function UNIQUELY within a merged program.
   *
   * <p>The merged program itself uses {@code enclosingClass + " :: " + signature} for exactly this
   * reason (see {@code MergedProgram.mergedKey}): a corpus like Juliet's declares the same method
   * name in thousands of files, and the signature alone does not distinguish them.
   *
   * <p>Anything that maps per-function state has to use this key. Keying it by the signature alone
   * makes every same-named method share one entry, which is both wrong and non-terminating.
   */
  private static String mergedKeyOf(TacFunction fn) {
    if (fn == null) return "";
    String enc = fn.enclosingClass();
    if (enc == null || enc.isEmpty()) return fn.signature();
    return enc + " :: " + fn.signature();
  }

  /**
   * Test seam for {@link #mergedKeyOf(TacFunction)}.
   *
   * <p>The uniqueness of this key is what makes the parameter-origin fixpoint converge, so the test
   * has to be able to compute it the same way the engine does. A test that reimplemented the key
   * would measure a different set of keys than the engine uses.
   */
  static String mergedKeyForTest(TacFunction fn) {
    return mergedKeyOf(fn);
  }

  /** {@code "SourceLayer.readRequestParam/1"} -> {@code "readRequestParam/1"}. */
  private static String nameArityOfCall(String calleeSig) {
    if (calleeSig == null) return "";
    String s = calleeSig;
    int sep = s.indexOf(" :: ");
    if (sep >= 0) s = s.substring(sep + 4);
    int slash = s.indexOf('/');
    if (slash < 0) return s;
    String head = s.substring(0, slash);
    int dot = head.lastIndexOf('.');
    if (dot > 0) head = head.substring(dot + 1);
    return head + s.substring(slash);
  }

  /**
   * The OWNER of a callee signature: {@code "SourceLayer.readRequestParam/1"} -> {@code
   * "SourceLayer"}.
   *
   * <p><b>This returns the owner, not the method name.</b> The two differ by one segment: for
   * {@code "demo.JavaSink.run/1"} the owner is {@code "demo.JavaSink"} and the method name is
   * {@code "run"}. Returning the method name made every owner-keyed lookup miss -- the key built
   * was {@code "run/1 :: run"} instead of {@code "run/1 :: demo.JavaSink"} -- so a cross-language
   * call never resolved to its callee, and the caller's taint stopped at the language boundary.
   */
  private static String qualifierOf(String calleeSig) {
    if (calleeSig == null) return null;
    String s = calleeSig;
    int sep = s.indexOf(" :: ");
    if (sep >= 0) s = s.substring(sep + 4);
    int slash = s.indexOf('/');
    String head = slash < 0 ? s : s.substring(0, slash);
    int dot = head.lastIndexOf('.');
    // Everything BEFORE the last dot is the owner; the last segment is the method.
    return dot > 0 ? head.substring(0, dot) : null;
  }

  /**
   * {@code "readRequestParam(String):String"} -> {@code "readRequestParam/1"}. Arity is the number
   * of top-level commas in the parameter list, so a generic parameter containing a comma ({@code
   * Map<String,Integer>}) is counted as one parameter, not two.
   */
  private static String nameArity(String fnSignature) {
    if (fnSignature == null) return "";
    String s = fnSignature;
    int sep = s.indexOf(" :: ");
    if (sep >= 0) s = s.substring(sep + 4);
    int paren = s.indexOf('(');
    if (paren < 0) return s;
    int close = matchingParen(s, paren);
    if (close < 0) return s;
    String name = s.substring(0, paren);
    int dot = name.lastIndexOf('.');
    if (dot > 0) name = name.substring(dot + 1);
    String params = s.substring(paren + 1, close);
    int arity = 0;
    int depth = 0;
    boolean any = false;
    for (int i = 0; i < params.length(); i++) {
      char c = params.charAt(i);
      if (c == '<' || c == '(') depth++;
      else if (c == '>' || c == ')') depth--;
      else if (c == ',' && depth == 0) arity++;
      if (!Character.isWhitespace(c)) any = true;
    }
    int n = any ? arity + 1 : 0;
    return name + "/" + n;
  }

  /** index of the ')' matching the '(' at {@code open}, or -1. */
  private static int matchingParen(String s, int open) {
    int depth = 0;
    for (int i = open; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '(') depth++;
      else if (c == ')') {
        depth--;
        if (depth == 0) return i;
      }
    }
    return -1;
  }

  private static String key(TacFunction fn, int paramIndex) {
    return fn.signature() + "#" + paramIndex;
  }

  /**
   * the entry-boundary placeholder for a parameter — the label every function starts with before
   * any caller has contributed a real origin. Used to tell "nothing propagated yet" apart from "a
   * real origin was already established", so a propagated origin can overwrite the placeholder
   * without being mistaken for a conflicting second source.
   */
  private static String placeholderFor(TacFunction fn, int paramIndex) {
    if (fn == null || paramIndex < 0 || paramIndex >= fn.params().size()) return null;
    return "param:" + fn.params().get(paramIndex).name();
  }

  public FindingReporter runParallel(TacProgram program, int threads) {
    Objects.requireNonNull(program, "program");
    if (threads <= 0) {
      return this.runSequential(program);
    }
    long t0 = System.currentTimeMillis();
    FindingReporter reporter = new FindingReporter();
    CallGraphStrategy strategy =
        CallGraphStrategyFactory.create(
            (String) this.callGraphStrategyName,
            (TacProgram) program,
            (Path) this.callGraphCachePath);
    LOG.info(
        "Building call graph via {} (parallel mode, {} workers)...",
        (Object) strategy.name(),
        (Object) threads);
    CallGraph cg = strategy.build();
    this.budget = new AnalysisBudget(this.config.tuning());
    this.orderCache = new FunctionOrderCache();
    AnalysisContext ctx =
        new AnalysisContext(program, cg, this.config, reporter, null, null, this.budget);
    ctx.setStaticFieldResolver(this.staticFieldResolver);
    reporter.setContext(ctx);
    reporter.setModuleScope(moduleScope());
    DfaEngine.registerSourcePaths(ctx, program, this.functionSourcePaths);
    this.runWorklist(ctx, program, cg, threads);
    long ms = System.currentTimeMillis() - t0;
    LOG.info(
        "DfaEngine run (parallel x{}): {} functions analysed, {} summarised, {} findings, {} ms",
        new Object[] {
          threads, ctx.factBag().operands().size(), ctx.summaryCache().size(), reporter.size(), ms
        });
    logBudgetReport(ctx);
    return reporter;
  }

  public FindingReporter runSequential(TacProgram program) {
    long t0 = System.currentTimeMillis();
    FindingReporter reporter = new FindingReporter();
    CallGraphStrategy strategy =
        CallGraphStrategyFactory.create(
            (String) this.callGraphStrategyName,
            (TacProgram) program,
            (Path) this.callGraphCachePath);
    LOG.info("Building call graph via {}...", (Object) strategy.name());
    CallGraph cg = strategy.build();
    this.budget = new AnalysisBudget(this.config.tuning());
    this.orderCache = new FunctionOrderCache();
    AnalysisContext ctx =
        new AnalysisContext(program, cg, this.config, reporter, null, null, this.budget);
    ctx.setStaticFieldResolver(this.staticFieldResolver);
    reporter.setContext(ctx);
    reporter.setModuleScope(moduleScope());
    DfaEngine.registerSourcePaths(ctx, program, this.functionSourcePaths);
    for (DfaChecker c : this.checkers) {
      try {
        c.initEngine(ctx);
      } catch (RuntimeException ex) {
        LOG.warn("Checker {} initEngine failed: {}", (Object) c.name(), (Object) ex.toString());
      }
    }
    ctx.guardStore().setBridge(boundedSolverBridge(ctx));
    this.warmUpMethodSummaries(ctx, program);
    this.computeParamOrigins(ctx, program, cg);
    this.solvePointsTo(ctx, program);
    int fnCount = 0;
    int summaryCount = 0;
    Set analysedOrigins = this.config.tuning().analysedOrigins();
    for (TacFunction fn : DfaEngine.orderedFunctions(program, cg)) {
      ++fnCount;
      if (!analysedOrigins.contains(fn.origin())) continue;
      ctx.factBag().clear();
      ctx.sideEffectConsumer().clear();
      this.analyseOneFunction(ctx, fn);
      FunctionSummary merged = this.summariseOneFunction(ctx, fn);
      if (!merged.isTrivial() || merged.hasBody()) {
        ctx.summaryCache().put(fn.signature(), merged);
        ++summaryCount;
      }
      this.r716putQualifiedAliasSummary(ctx, fn, merged);
    }
    for (DfaChecker c : this.checkers) {
      try {
        c.finish(ctx);
      } catch (RuntimeException ex) {
        LOG.warn("Checker {} finish failed: {}", (Object) c.name(), (Object) ex.toString());
      }
    }
    long ms = System.currentTimeMillis() - t0;
    LOG.info(
        "DfaEngine run: {} functions analysed (of {}), {} summarised, {} findings, {} ms",
        new Object[] {fnCount, program.functions().size(), summaryCount, reporter.size(), ms});
    logBudgetReport(ctx);
    return reporter;
  }

  /*
   * WARNING - Removed try catching itself - possible behaviour change.
   */
  private void runWorklist(AnalysisContext ctx, TacProgram program, CallGraph cg, int threads) {
    FunctionNode node;
    for (DfaChecker c : this.checkers) {
      try {
        c.initEngine(ctx);
      } catch (RuntimeException ex) {
        LOG.warn("Checker {} initEngine failed: {}", (Object) c.name(), (Object) ex.toString());
      }
    }
    for (DfaChecker c : this.checkers) {
      if (!(c instanceof TaintedCheckerBase)) continue;
      TaintedCheckerBase tcb = (TaintedCheckerBase) c;
      if (tcb.solverBridge() == null) continue;
      ctx.guardStore().setBridge(boundedSolverBridge(ctx));
      break;
    }
    this.warmUpMethodSummaries(ctx, program);
    this.computeParamOrigins(ctx, program, cg);
    this.solvePointsTo(ctx, program);
    Set analysedOrigins = this.config.tuning().analysedOrigins();
    ConcurrentHashMap<String, AtomicInteger> deps = new ConcurrentHashMap<String, AtomicInteger>();
    for (TacFunction fn : program.functions()) {
      if (!analysedOrigins.contains(fn.origin())) continue;
      int calleeCount = 0;
      node = new FunctionNode(fn.signature());
      for (CallSite cs : cg.callsFrom(node)) {
        if (cs.callee().signature().equals(fn.signature())) continue;
        if (program.function(cs.callee().signature()) == null) continue;
        ++calleeCount;
      }
      deps.put(fn.signature(), new AtomicInteger(calleeCount));
    }
    HashMap<String, List> callersOf = new HashMap<String, List>();
    for (TacFunction fn : program.functions()) {
      node = new FunctionNode(fn.signature());
      for (CallSite cs : cg.callsFrom(node)) {
        String calleeSig = cs.callee().signature();
        if (calleeSig.equals(fn.signature()) || program.function(calleeSig) == null) continue;
        callersOf.computeIfAbsent(calleeSig, k -> new ArrayList()).add(fn.signature());
      }
    }
    ConcurrentLinkedQueue<String> readyQ = new ConcurrentLinkedQueue<String>();
    for (Map.Entry e : deps.entrySet()) {
      if (((AtomicInteger) e.getValue()).get() != 0) continue;
      readyQ.add((String) e.getKey());
    }
    AtomicInteger processed = new AtomicInteger(0);
    int total = deps.size();
    ExecutorService exec =
        Executors.newFixedThreadPool(threads, DfaEngine.namedThreadFactory("plumb-dfa-worker"));
    Phaser phaser = new Phaser(1);
    // Per-worker checkers: a checker carries per-function state, so each worker thread
    // needs its own instances. See DfaEngine#checkersForWorker.
    ThreadLocal<List<DfaChecker>> workerCheckers =
        ThreadLocal.withInitial(
            () -> {
              List<DfaChecker> cs = checkersForWorker();
              for (DfaChecker c : cs) {
                try {
                  c.initEngine(ctx);
                } catch (RuntimeException ex) {
                  LOG.warn("Checker {} initEngine failed: {}", c.name(), ex.toString());
                }
              }
              return cs;
            });
    try {
      while (true) {
        String polled;
        if ((polled = (String) readyQ.poll()) != null) {
          String sig = polled;
          phaser.register();
          exec.submit(
              () -> {
                try {
                  List cs;
                  TacFunction fn = program.function(sig);
                  if (fn != null && analysedOrigins.contains(fn.origin())) {
                    List<DfaChecker> mine = workerCheckers.get();
                    ctx.factBag().clear();
                    ctx.sideEffectConsumer().clear();
                    this.analyseOneFunction(ctx, fn, mine);
                    FunctionSummary merged = this.summariseOneFunction(ctx, fn, mine);
                    if (!merged.isTrivial() || merged.hasBody()) {
                      ctx.summaryCache().put(sig, merged);
                    }
                    this.r716putQualifiedAliasSummary(ctx, fn, merged);
                  }
                  List<String> callerSigs = callersOf.get(sig);
                  if (callerSigs != null) {
                    for (String callerSig : callerSigs) {
                      AtomicInteger c = (AtomicInteger) deps.get(callerSig);
                      if (c == null || c.decrementAndGet() != 0) continue;
                      readyQ.add(callerSig);
                    }
                  }
                } catch (RuntimeException ex) {
                  LOG.warn(
                      "Parallel analysis of {} failed: {}", (Object) sig, (Object) ex.toString());
                } finally {
                  processed.incrementAndGet();
                  phaser.arriveAndDeregister();
                }
              });
          continue;
        }
        if (phaser.getRegisteredParties() == 1) {
          break;
        }
        phaser.arriveAndAwaitAdvance();
      }
    } finally {
      exec.shutdown();
      try {
        if (!exec.awaitTermination(60L, TimeUnit.SECONDS)) {
          exec.shutdownNow();
        }
      } catch (InterruptedException ie) {
        exec.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    for (DfaChecker c : this.checkers) {
      try {
        c.finish(ctx);
      } catch (RuntimeException ex) {
        LOG.warn("Checker {} finish failed: {}", (Object) c.name(), (Object) ex.toString());
      }
    }
    LOG.info(
        "DfaEngine parallel worklist: {} of {} functions processed",
        (Object) processed.get(),
        (Object) total);
  }

  private void rerunOneFn(TacFunction fn2, AnalysisContext ctx) {
    ctx.factBag().clear();
    ctx.sideEffectConsumer().clear();
    this.analyseOneFunction(ctx, fn2);
    FunctionSummary merged = this.summariseOneFunction(ctx, fn2);
    if (!merged.isTrivial() || merged.hasBody()) {
      ctx.summaryCache().put(fn2.signature(), merged);
    }
    this.r716putQualifiedAliasSummary(ctx, fn2, merged);
  }

  /*
   * WARNING - Removed try catching itself - possible behaviour change.
   */
  private void analyseOneFunction(AnalysisContext ctx, TacFunction fn) {
    analyseOneFunction(ctx, fn, this.checkers);
  }

  /**
   * Analyse one function with an explicit checker list.
   *
   * <p>The list is a parameter because the parallel worklist gives every worker its OWN checkers: a
   * checker holds per-function state, and sharing one instance between workers would have one
   * worker's {@code initFunction} clear another worker's facts mid-walk.
   */
  /**
   * Whether any checker in this run will consult the solver for a path condition.
   *
   * <p>The engine's own {@code path_sensitivity} setting decides it. {@code "none"} switches the
   * verification off, and the engine then skips collecting the guards it would feed -- the
   * collection is real work per instruction, and a run that will not use the facts should not pay
   * for them.
   */
  private boolean pathVerificationEnabled(AnalysisContext ctx) {
    try {
      if (this.config == null || this.config.tuning() == null) return false;
      String mode =
          org.plumb.dfa.taint.TaintedCheckerBase.normalisePathSensitivity(
              this.config.tuning().pathSensitivity());
      return !"none".equals(mode);
    } catch (RuntimeException e) {
      // A configuration problem must not take the analysis down. The conservative answer is
      // to collect the guards: collecting them costs time, and not collecting them would
      // silently disable a filter.
      return true;
    }
  }

  /**
   * Walk the function's instructions once and collect the guard facts they prove.
   *
   * <p>The tracker is fed the same order the checkers see, so the facts it holds are the ones that
   * hold at the point each checker asks.
   */
  private org.plumb.dfa.taint.GuardTracker collectFunctionGuards(
      TacFunction fn, List<Integer> order) {
    org.plumb.dfa.taint.GuardTracker tracker = new org.plumb.dfa.taint.GuardTracker();
    try {
      for (int idx : order) {
        TacInstruction ins = fn.instructionAt(idx);
        if (ins == null) continue;
        // Record the guards IN FORCE at this instruction BEFORE tracking it. The snapshot
        // describes the state the instruction is reached in, so a sink at this position can
        // ask "what was true here?" rather than "what was true somewhere in this function".
        tracker.recordPosition(idx);
        tracker.track(ins);
      }
    } catch (RuntimeException e) {
      // A tracker failure costs the path condition, not the analysis: the checkers still run
      // and simply find no guards to consult.
      LOG.warn("Guard collection failed on {}: {}", fn.signature(), e.toString());
    }
    return tracker;
  }

  private void analyseOneFunction(AnalysisContext ctx, TacFunction fn, List<DfaChecker> checkers) {
    ctx.setCurrentFunction(fn);
    // Start the per-function clock. The budget's time checks compare against this, so a
    // per-function timeout means "this function took too long" rather than "the whole run
    // took too long".
    ctx.startFunctionTimer();
    // The analysis order is computed once per function and shared by every checker.
    // analysisOrder() walks the CFG, detects back edges, computes natural loops and
    // reorders successors -- it is not cheap, and it used to be recomputed for every
    // checker on every function. With 78 registered checkers that was 78 identical
    // CFG walks per function. It is cached per function below and reused.
    List<Integer> order = orderCacheFor(ctx).analysisOrder(fn);
    // The guard facts are a property of the FUNCTION, so they are collected once here and
    // shared by every checker, rather than each checker deriving its own. Two checkers
    // deriving their own is how they end up disagreeing about whether a value was guarded.
    //
    // The walk is skipped when no checker will consult the solver: the tracker does real work
    // per instruction, and a run with path verification off should not pay for it.
    ctx.setFunctionGuards(pathVerificationEnabled(ctx) ? collectFunctionGuards(fn, order) : null);
    try {
      for (DfaChecker c : checkers) {
        if (!DfaEngine.isEnabled(c.config(ctx))) continue;
        // Language gate. A checker that declares it does not handle this function's language is
        // skipped entirely -- this is a skip, not a finding.
        if (!supportsChecker(c, fn)) continue;
        try {
          c.initFunction(ctx, fn);
          for (int idx : order) {
            // Budget check. Once the per-function time budget is spent, stop walking this
            // function's instructions. The exhaustion is recorded by the budget, so the
            // user can see that the function was only partly analysed.
            if (!ctx.withinTimeBudget()) {
              LOG.warn(
                  "Per-function time budget spent; stopped analysing {} at instruction {}",
                  new Object[] {fn.signature(), Integer.valueOf(idx)});
              break;
            }
            TacInstruction ins = fn.instructionAt(idx);
            // The instruction's position is what makes a PATH-scoped path condition
            // possible: the guards in force at a sink are the ones established by the
            // branches that lead to it. The checkers read this to ask "what was true
            // here?" rather than "what was true somewhere in this function".
            ctx.setCurrentInstructionIndex(idx);
            if (ins.kind == TacInstruction.Kind.CALL) {
              try {
                recordModuleCrossing(ctx, fn, ins);
                SummaryApplier.apply(ins, ctx, ins.args);
                ctx.sideEffectConsumer().recordCall(ins, ctx);
              } catch (RuntimeException re) {
                LOG.warn(
                    "SummaryApplier failed on {}: {}",
                    (Object) ins.calleeSig,
                    (Object) re.toString());
              }
            }
            c.checkInstruction(ins, fn, ctx);
          }
          c.finishFunction(ctx, fn);
        } catch (OutOfMemoryError oom) {
          // An out-of-memory in ONE function must not take the whole scan down. The function
          // is reported as unanalysed and the scan continues with the rest: a scan that dies
          // produces no report at all, which is worse than a report that says "this function
          // was not analysed".
          //
          // The stack trace is NOT logged: printing it allocates, and the allocation is
          // what failed. The message is enough to say which function ran out.
          LOG.warn(
              "out of memory while analysing {}; the function was not analysed", fn.signature());
          if (budget != null) {
            budget.record(
                AnalysisBudget.Dimension.WORKLIST_SIZE,
                Integer.MAX_VALUE,
                fn.signature() + " (out of memory)");
          }
        } catch (RuntimeException ex) {
          LOG.warn(
              "Checker {} failed on {}: {}",
              new Object[] {c.name(), fn.signature(), ex.toString()});
        }
      }
    } finally {
      ctx.setCurrentFunction(null);
    }
  }

  /**
   * Record a call that leaves the selected module.
   *
   * <p>The point of a single-module analysis is that the call graph stays WHOLE across the module
   * boundary, so the taint still reaches the sinks inside the module. The cost of that is that some
   * of the flow comes from outside, and a reader of the report has to be able to see that.
   *
   * <p>So the crossing is recorded, not prevented. It is the diagnostic that says "this module was
   * reached from outside", which is exactly the case the user called a normal leak.
   */
  private void recordModuleCrossing(AnalysisContext ctx, TacFunction fn, TacInstruction ins) {
    ModuleScope scope = moduleScope();
    if (!scope.isLimited()) return;
    String callerModule = fn.module();
    if (callerModule == null || !scope.contains(callerModule)) return;
    scope.observeModule(callerModule);
    String calleeSig = ins.calleeSig;
    if (calleeSig == null || calleeSig.isEmpty()) return;
    // The callee's module is resolved from the call graph's view: the function it points at,
    // when there is one. A callee that resolves to nothing is recorded as unresolved, which
    // is a different kind of gap.
    TacFunction callee = ctx.program() == null ? null : ctx.program().function(calleeSig);
    String calleeModule = callee == null ? null : callee.module();
    if (calleeModule == null) {
      scope.recordCrossing(fn.signature(), calleeSig, false);
    } else if (!scope.contains(calleeModule)) {
      scope.observeModule(calleeModule);
      scope.recordCrossing(fn.signature(), calleeSig, true);
    }
  }

  /**
   * Whether a checker applies to a function.
   *
   * <p>Consults {@link DfaChecker#supports(TacFunction)}. A checker that throws while answering is
   * treated as "not supported" -- a broken gate must not take down the whole analysis, and the safe
   * answer is to skip that one checker for that one function.
   */
  private static boolean supportsChecker(DfaChecker c, TacFunction fn) {
    try {
      return c.supports(fn);
    } catch (RuntimeException ex) {
      LOG.warn(
          "Checker {} failed its supports() check for {}: {}",
          new Object[] {c.name(), fn.signature(), ex.toString()});
      return false;
    }
  }

  private static void registerSourcePaths(
      AnalysisContext ctx, TacProgram program, Map<String, Path> functionSourcePaths) {
    Path singlePath;
    if (functionSourcePaths != null && !functionSourcePaths.isEmpty()) {
      for (Map.Entry<String, Path> e : functionSourcePaths.entrySet()) {
        ctx.registerSourcePath(e.getKey(), e.getValue());
      }
    }
    if ((singlePath = program.sourcePath()) != null) {
      for (TacFunction fn : program.functions()) {
        String enc = fn.enclosingClass();
        if (enc != null && !enc.isEmpty()) {
          ctx.registerSourcePath(enc, singlePath);
        }
        // A TOP-LEVEL function has no enclosing class -- a Kotlin or Scala
        // file-level function, or a Java file's package-private helper
        // declared outside any class. Registering only by enclosing class
        // leaves those functions with no path, and a finding in one is
        // reported as file="unknown". The signature is the identity that is
        // ALWAYS present, so it is registered as well.
        String sig = fn.signature();
        if (sig != null && !sig.isEmpty()) {
          ctx.registerSourcePath(sig, singlePath);
        }
      }
      return;
    }
    if (functionSourcePaths == null || functionSourcePaths.isEmpty()) {
      LOG.warn(
          "TacProgram.sourcePath() is null and no functionSourcePaths map was set; new DFA checker"
              + " findings will report filePath='unknown'");
    }
  }

  private FunctionSummary summariseOneFunction(AnalysisContext ctx, TacFunction fn) {
    return summariseOneFunction(ctx, fn, this.checkers);
  }

  /**
   * Summarise one function with an explicit checker list. The parallel worklist passes the worker's
   * own checkers, for the same reason {@link #analyseOneFunction(AnalysisContext,
   * org.plumb.tac.ir.TacFunction, List)} takes them: the summary is built from the checker's
   * per-function state, which belongs to one worker.
   */
  private FunctionSummary summariseOneFunction(
      AnalysisContext ctx, TacFunction fn, List<DfaChecker> checkers) {
    ctx.setCurrentFunction(fn);
    FunctionSummary merged = FunctionSummary.empty();
    for (DfaChecker c : checkers) {
      FunctionSummary s;
      if (!DfaEngine.isEnabled(c.config(ctx))) continue;
      // Same language gate as the analysis pass: a checker that does not handle this function's
      // language must not contribute a summary for it either, or the summary would carry
      // observations the checker never actually made.
      if (!supportsChecker(c, fn)) continue;
      try {
        s = c.summarizeFunction(ctx, fn);
      } catch (RuntimeException ex) {
        LOG.warn(
            "Checker {} summarizeFunction failed on {}: {}",
            new Object[] {c.name(), fn.signature(), ex.toString()});
        s = FunctionSummary.empty();
      }
      if (s == null) {
        s = FunctionSummary.empty();
      }
      merged = merged.merge(s);
    }
    // The points-to fact about the return value, attached to the summary.
    //
    // The points-to solver knows which objects a method may return, but that
    // knowledge stayed in the solver: nothing carried it into the summary, and
    // nothing read it back. The operation existed as a RECORD with no producer
    // and no consumer -- a declared capability that changed nothing.
    //
    // Attaching it here makes the return value's object identity travel with the
    // summary, so a caller can refine its own points-to set for the value it
    // received. That is what makes two instances of one class separable: the
    // caller's dest inherits the CALLEE's alloc sites rather than merging with
    // every other allocation of that type.
    merged = attachReturnPointsTo(ctx, fn, merged);
    return merged;
  }

  /**
   * Attach the function's return-value points-to set to its summary.
   *
   * <p>The set is the union over the method's contexts, which is a sound over-approximation: a
   * caller does not know which context it will run in, so it must be able to receive any of the
   * objects the method may return.
   *
   * <p>No operation is attached when the set is empty, so a method that returns nothing tracked
   * leaves its summary exactly as it was.
   *
   * @param ctx the analysis context
   * @param fn the function being summarised
   * @param summary the summary so far
   * @return the summary with the points-to operation attached
   */
  private static FunctionSummary attachReturnPointsTo(
      AnalysisContext ctx, TacFunction fn, FunctionSummary summary) {
    if (ctx == null || fn == null) return summary;
    org.plumb.dfa.points.PointsToSolver solver = ctx.pointsToSolver();
    if (solver == null) return summary;
    java.util.Set<org.plumb.dfa.points.AbstractObject.Obj> objects;
    try {
      objects = solver.returnObjectsOf(fn.signature());
    } catch (RuntimeException e) {
      // A points-to query that fails costs the refinement, not the analysis.
      return summary;
    }
    if (objects == null || objects.isEmpty()) return summary;
    java.util.Set<String> labels = new java.util.LinkedHashSet<>();
    for (org.plumb.dfa.points.AbstractObject.Obj o : objects) {
      if (o != null) {
        try {
          labels.add(o.label());
        } catch (RuntimeException e) {
          // A malformed object label is skipped; the rest of the set still applies.
        }
      }
    }
    if (labels.isEmpty()) return summary;
    FunctionSummary.Builder b = new FunctionSummary.Builder(summary.signature());
    b.recordReturnOp(new org.plumb.dfa.summary.Operation.PointsToPropagate(labels));
    // The builder starts empty, so the ORIGINAL summary's observations have to be
    // carried over explicitly. Returning a summary that dropped them would erase
    // every taint fact the function published.
    FunctionSummary carried = summary.merge(b.build());
    return carried;
  }

  private void buildAndStoreMethodSummary(AnalysisContext ctx, TacFunction fn) {
    try {
      FunctionSignature sig = DfaEngine.parseFnSignature(fn);
      ctx.methodSummaryCache().put(sig, fn);
    } catch (RuntimeException ex) {
      LOG.warn(
          "MethodSummaryBuilder failed on {}: {}", (Object) fn.signature(), (Object) ex.toString());
    }
  }

  private static FunctionSignature parseFnSignature(TacFunction fn) {
    String s = fn.signature();
    String name = fn.name();
    if (s == null || s.isEmpty()) {
      return new FunctionSignature(
          "", "<unknown>", name == null ? "<unknown>" : name, List.of(), "?");
    }
    FunctionSignature parsed = null;
    try {
      parsed = FunctionSignature.parse((String) s);
    } catch (RuntimeException runtimeException) {
      // empty catch block
    }
    if (parsed != null) {
      return parsed;
    }
    return new FunctionSignature(
        "", "<unknown>", name == null ? "<unknown>" : name, List.of(), "?");
  }

  public static List<Integer> bbRpoOrder(TacFunction fn) {
    Object succs;
    Objects.requireNonNull(fn, "fn");
    TacCfg cfg = TacCfgBuilder.build((TacFunction) fn);
    if (cfg.blocks().isEmpty()) {
      return List.of();
    }
    ArrayList<Integer> postOrder = new ArrayList<Integer>();
    HashSet<Integer> visited = new HashSet<Integer>();
    ArrayDeque<int[]> stack = new ArrayDeque<int[]>();
    stack.push(new int[] {cfg.entryId(), 0});
    while (!stack.isEmpty()) {
      int[] top = (int[]) stack.peek();
      int blockId = top[0];
      int childIdx = top[1];
      if (childIdx == 0 && !visited.add(blockId)) {
        stack.pop();
        continue;
      }
      java.util.List<Integer> blockSuccs = cfg.succs(blockId);
      if (childIdx < blockSuccs.size()) {
        top[1] = childIdx + 1;
        int nextSucc = blockSuccs.get(childIdx);
        if (visited.contains(nextSucc)) continue;
        stack.push(new int[] {nextSucc, 0});
        continue;
      }
      stack.pop();
      postOrder.add(blockId);
    }
    ArrayList<Integer> rpoBlocks = new ArrayList<Integer>(postOrder.size());
    for (int i = postOrder.size() - 1; i >= 0; --i) {
      rpoBlocks.add((Integer) postOrder.get(i));
    }
    ArrayList<Integer> out = new ArrayList<Integer>();
    HashSet<Integer> seen = new HashSet<Integer>();
    java.util.Iterator<Integer> rpoIter = rpoBlocks.iterator();
    while (rpoIter.hasNext()) {
      int blockId = rpoIter.next();
      TacBasicBlock block = cfg.block(blockId);
      if (block == null) continue;
      java.util.Iterator<Integer> blockIter = block.indices().iterator();
      while (blockIter.hasNext()) {
        int idx = blockIter.next();
        out.add(idx);
        seen.add(idx);
      }
    }
    int n = fn.instructions().size();
    for (int i = 0; i < n; ++i) {
      if (seen.contains(i)) continue;
      out.add(i);
    }
    return out;
  }

  /**
   * The instruction order used by the forward dataflow analysis.
   *
   * <p>For a function with no loops this is exactly {@link #bbRpoOrder(TacFunction)}: the blocks in
   * reverse post-order, each block's instructions in source order.
   *
   * <p><b>Why a loop needs a different order.</b> The plain RPO is a valid topological order of the
   * CFG, but a loop makes it useless for a forward-flow analysis. In
   *
   * <pre>
   *   String x = "safe";
   *   while (i &lt; 3) { if (i == 1) x = userInput; i++; }
   *   exec(x);
   * </pre>
   *
   * the depth-first search that produces the RPO finishes the loop's <em>exit</em> block before the
   * loop's <em>body</em>: the exit is a short path, the body loops back to the condition, so the
   * body is popped from the stack first and therefore lands LAST in the post-order. The result is
   * that the assignment {@code x = userInput} is analysed <em>after</em> the sink that reads {@code
   * x}, and the analysis reports a clean program for code that hands attacker input to the
   * operating system.
   *
   * <p><b>The fix.</b> The order is computed so that every loop's body is visited before the loop's
   * exit. Concretely: the back edges are identified first, the natural loop of each back edge is
   * computed, and the depth-first search is then run with the successor order chosen so that a
   * successor which continues a loop is explored before a successor which leaves it.
   *
   * <p>This is a heuristic, not a fixpoint: a single pass still cannot see a taint that is produced
   * in the body and consumed at the exit on the FIRST pass through the exit block. The engine
   * compensates by re-walking the function until the tainted state stops growing -- see {@link
   * #analyseOneFunction(AnalysisContext, TacFunction)}. The ordering change is what makes that
   * re-walk converge in a couple of passes instead of one per loop level.
   *
   * @param fn the function to order
   * @return the instruction indices in analysis order
   */
  public static List<Integer> analysisOrder(TacFunction fn) {
    Objects.requireNonNull(fn, "fn");
    TacCfg cfg = TacCfgBuilder.build(fn);
    if (cfg.blocks().isEmpty()) {
      return List.of();
    }
    // Identify the back edges: an edge u -> v where v is an ancestor of u in the depth-first
    // search tree. Those are the edges that close a loop.
    java.util.Map<Integer, Integer> dfsParent = new HashMap<>();
    ArrayList<Integer> postOrder = new ArrayList<>();
    HashSet<Integer> visited = new HashSet<>();
    ArrayDeque<int[]> stack = new ArrayDeque<>();
    stack.push(new int[] {cfg.entryId(), 0});
    while (!stack.isEmpty()) {
      int[] top = stack.peek();
      int blockId = top[0];
      int childIdx = top[1];
      if (childIdx == 0 && !visited.add(blockId)) {
        stack.pop();
        continue;
      }
      java.util.List<Integer> blockSuccs = cfg.succs(blockId);
      if (childIdx < blockSuccs.size()) {
        top[1] = childIdx + 1;
        int nextSucc = blockSuccs.get(childIdx);
        if (visited.contains(nextSucc)) continue;
        dfsParent.put(nextSucc, blockId);
        stack.push(new int[] {nextSucc, 0});
        continue;
      }
      stack.pop();
      postOrder.add(blockId);
    }
    // The blocks that are loop headers: the targets of the back edges.
    HashSet<Integer> loopHeaders = new HashSet<>();
    // The blocks that are inside some loop: every block on a path from a loop header to the
    // back-edge source, plus the header itself.
    HashSet<Integer> inLoop = new HashSet<>();
    //
    // A back edge is an edge u -> v where v is an ANCESTOR of u in the depth-first search tree.
    // The DFS tree edges are recorded in `dfsParent` (child -> parent), so the ancestors of a
    // node are the chain obtained by following `dfsParent` upwards. For every block u and every
    // successor v of u, v is a back-edge target when the upward chain from u reaches v.
    for (Map.Entry<Integer, java.util.List<Integer>> e : cfg.successors().entrySet()) {
      int u = e.getKey();
      for (int v : e.getValue()) {
        int walker = u;
        int guard = 0;
        boolean isBackEdge = false;
        while (guard++ < dfsParent.size() + 1) {
          if (walker == v) {
            isBackEdge = true;
            break;
          }
          Integer next = dfsParent.get(walker);
          if (next == null) break;
          walker = next;
        }
        if (!isBackEdge) continue;
        loopHeaders.add(v);
        // The natural loop of the back edge v -> u: the header v, every block on the DFS parent
        // chain from u back to v, and v itself.
        inLoop.add(v);
        int p = u;
        int g2 = 0;
        while (g2++ < dfsParent.size() + 1) {
          inLoop.add(p);
          if (p == v) break;
          Integer next = dfsParent.get(p);
          if (next == null) break;
          p = next;
        }
      }
    }
    if (loopHeaders.isEmpty()) {
      // No loop: the plain RPO is already a correct forward order.
      return bbRpoOrder(fn);
    }
    // Re-run the depth-first search with a successor order that leaves the loop as early as
    // possible: a successor that exits every loop is explored BEFORE one that stays inside a loop.
    //
    // The post-order is built by popping, and the RPO is its reverse, so the block that finishes
    // FIRST ends up LAST in the analysis order. Exploring the exit first therefore puts the loop
    // EXIT late in the order -- after the body -- which is exactly what a forward-flow analysis
    // needs: the body has already been analysed when the exit is reached.
    java.util.Map<Integer, java.util.List<Integer>> reordered = new HashMap<>();
    for (Map.Entry<Integer, java.util.List<Integer>> e : cfg.successors().entrySet()) {
      java.util.List<Integer> blockSuccs = e.getValue();
      java.util.List<Integer> sorted = new ArrayList<>(blockSuccs);
      // Stable partition: the successors that are NOT inside a loop come first.
      sorted.sort(
          new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
              boolean aIn = inLoop.contains(a);
              boolean bIn = inLoop.contains(b);
              if (aIn == bIn) return 0;
              return aIn ? 1 : -1;
            }
          });
      reordered.put(e.getKey(), sorted);
    }
    postOrder = new ArrayList<>();
    visited = new HashSet<>();
    stack = new ArrayDeque<>();
    stack.push(new int[] {cfg.entryId(), 0});
    while (!stack.isEmpty()) {
      int[] top = stack.peek();
      int blockId = top[0];
      int childIdx = top[1];
      if (childIdx == 0 && !visited.add(blockId)) {
        stack.pop();
        continue;
      }
      java.util.List<Integer> blockSuccs = reordered.get(blockId);
      if (blockSuccs == null) {
        blockSuccs = cfg.succs(blockId);
      }
      if (childIdx < blockSuccs.size()) {
        top[1] = childIdx + 1;
        int nextSucc = blockSuccs.get(childIdx);
        if (visited.contains(nextSucc)) continue;
        stack.push(new int[] {nextSucc, 0});
        continue;
      }
      stack.pop();
      postOrder.add(blockId);
    }
    ArrayList<Integer> rpoBlocks = new ArrayList<>(postOrder.size());
    for (int i = postOrder.size() - 1; i >= 0; --i) {
      rpoBlocks.add(postOrder.get(i));
    }
    ArrayList<Integer> out = new ArrayList<>();
    HashSet<Integer> seen = new HashSet<>();
    for (Integer blockId : rpoBlocks) {
      TacBasicBlock block = cfg.block(blockId);
      if (block == null) continue;
      for (Integer idx : block.indices()) {
        out.add(idx);
        seen.add(idx);
      }
    }
    int n = fn.instructions().size();
    for (int i = 0; i < n; ++i) {
      if (seen.contains(i)) continue;
      out.add(i);
    }
    return out;
  }

  private static List<TacFunction> orderedFunctions(TacProgram program, CallGraph cg) {
    ArrayList<TacFunction> out = new ArrayList<TacFunction>();
    HashSet<TacFunction> seen = new HashSet<TacFunction>();
    for (FunctionNode node : cg.reversePostOrder()) {
      TacFunction fn = program.function(node.signature());
      if (fn == null || !seen.add(fn)) continue;
      out.add(fn);
    }
    for (TacFunction fn : program.functions()) {
      if (!seen.add(fn)) continue;
      out.add(fn);
    }
    return out;
  }

  private static String r40AliasForFunction(TacFunction fn) {
    int close;
    String sig = fn.signature();
    if (sig == null || sig.isEmpty()) {
      return null;
    }
    int paren = sig.indexOf(40);
    if (paren <= 0) {
      return null;
    }
    String methodName = sig.substring(0, paren);
    int dot = methodName.lastIndexOf(46);
    if (dot >= 0) {
      methodName = methodName.substring(dot + 1);
    }
    if ((close = sig.indexOf(41, paren)) < 0) {
      return null;
    }
    String paramList = sig.substring(paren + 1, close);
    int arity = 0;
    if (!paramList.isEmpty()) {
      int depth = 0;
      int last = 0;
      for (int i = 0; i < paramList.length(); ++i) {
        char c = paramList.charAt(i);
        if (c == '<') {
          ++depth;
          continue;
        }
        if (c == '>') {
          --depth;
          continue;
        }
        if (c != ',' || depth != 0) continue;
        if (i > last) {
          ++arity;
        }
        last = i + 1;
      }
      if (paramList.length() > last) {
        ++arity;
      }
    }
    return methodName + "/" + arity;
  }

  private void r716putQualifiedAliasSummary(
      AnalysisContext ctx, TacFunction fn, FunctionSummary merged) {
    Object alias = DfaEngine.r40AliasForFunction(fn);
    if (alias == null || ((String) alias).equals(fn.signature())) {
      // The alias IS the signature -- nothing extra to store. But the CANONICAL key may
      // still differ from the signature, so that is stored below.
      putCanonicalSummary(ctx, fn, merged);
      return;
    }
    String enclosing = fn.enclosingClass();
    if (enclosing != null && !enclosing.isEmpty()) {
      alias = enclosing + " :: " + (String) alias;
    }
    if (merged.hasBody()) {
      ctx.summaryCache().put((String) alias, merged);
    } else {
      FunctionSummary.Builder aliasBuilder = new FunctionSummary.Builder((String) alias);
      for (Map.Entry entry : merged.paramOps().entrySet()) {
        for (org.plumb.dfa.summary.Operation op :
            (Set<org.plumb.dfa.summary.Operation>) entry.getValue()) {
          aliasBuilder.recordParamOp(((Integer) entry.getKey()).intValue(), op);
        }
      }
      for (org.plumb.dfa.summary.Operation op : merged.returnOps()) {
        aliasBuilder.recordReturnOp(op);
      }
      for (org.plumb.dfa.summary.StringOp sop : merged.stringOps()) {
        aliasBuilder.recordStringOp(sop);
      }
      for (org.plumb.dfa.summary.FieldOp fop : merged.fieldOps()) {
        aliasBuilder.recordFieldOp(fop);
      }
      aliasBuilder.markHasBody();
      ctx.summaryCache().put((String) alias, aliasBuilder.build());
    }
    putCanonicalSummary(ctx, fn, merged);
  }

  /**
   * Stores a function's summary under its canonical callee key.
   *
   * <p><b>Why this is needed.</b> A function's own signature is whatever its frontend produced: a
   * Java method is {@code "run(java.lang.String):void"}, a Kotlin one is {@code
   * "callJava(java.lang.String):void"}, and a call site may name either target as {@code
   * "JavaSink.run/1"}. Those are three strings for one method.
   *
   * <p>The canonical key ({@code owner.method/arity}) is the one form every participant agrees on,
   * so a summary stored under it is findable from any call site's spelling. Without this, a
   * cross-language call finds no summary: the taint stops at the boundary, and the sink inside the
   * callee never sees the caller's data.
   *
   * <p>Both the qualified canonical form and the bare one are stored, because a function whose own
   * signature carries no owner (a top-level function) can only be reached by the bare key.
   */
  private void putCanonicalSummary(AnalysisContext ctx, TacFunction fn, FunctionSummary merged) {
    String canonical = org.plumb.tac.ir.CalleeKeyNormalizer.canonicalCalleeKey(fn.signature());
    if (canonical == null) return;
    // The canonical key of the SIGNATURE may carry no owner: a Java method's signature is
    // "run(java.lang.String):void", and the canonical form of that is "run/1" -- no owner.
    // But the CALL SITE names the owner ("JavaSink.run/1"), because the call site knows which
    // class it is calling into.
    //
    // So the owner is taken from the function's ENCLOSING CLASS, the same source the call
    // graph's owner-aware index uses. Without this the summary is stored under a key the call
    // site never asks for, and the taint stops at the language boundary.
    String ownerQualified = canonical;
    String enclosing = fn.enclosingClass();
    if (enclosing != null && !enclosing.isEmpty() && !canonical.contains(".")) {
      ownerQualified = enclosing + "." + canonical;
      // The owner may be spelled fully qualified here and simply at the call site (or the
      // other way round): a Java declaration's enclosing class is "JavaSink", while a Kotlin
      // call may name it "demo.JavaSink". Both spellings are stored, so the call site finds
      // the summary whichever it uses.
      int lastDot = enclosing.lastIndexOf('.');
      if (lastDot >= 0) {
        String simple = enclosing.substring(lastDot + 1);
        if (!simple.isEmpty() && !simple.equals(enclosing)) {
          ctx.summaryCache().put(simple + "." + canonical, merged);
        }
      }
    }
    String bare = org.plumb.tac.ir.CalleeKeyNormalizer.bareCalleeKey(canonical);
    for (String key : new String[] {ownerQualified, canonical, bare}) {
      if (key == null) continue;
      if (key.equals(fn.signature())) continue;
      FunctionSummary existing = ctx.summaryCache().get(key);
      // Do not overwrite a summary that is already there: another function may share the
      // bare key, and last-write-wins would pick an arbitrary one.
      if (existing != null && (!existing.isTrivial() || existing.hasBody())) continue;
      ctx.summaryCache().put(key, merged);
    }
  }

  private static boolean isEnabled(CheckerConfig cfg) {
    return cfg == null || cfg.enabled();
  }

  private static ThreadFactory namedThreadFactory(String prefix) {
    AtomicInteger n = new AtomicInteger(0);
    return r -> {
      Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }
}

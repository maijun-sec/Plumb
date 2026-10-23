package org.plumb.dfa.engine;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
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
 *   <li>For each function in {@link CallGraph#reversePostOrder()}: {@code initFunction
 *       : checkInstruction* : finishFunction : summarizeFunction}, then put the summary in the
 *       cache.
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
 *   <li>{@link #bbRpoOrder(TacFunction)} : BB-RPO, and <b>forward</b> inside each block.
 *       Blocks are emitted in reverse post-order; the instructions inside a block keep their
 *       original forward order. Single-block functions (the common case for small getters /
 *       setters) therefore get exactly the linear instruction order, so this is
 *       backward-compatible for the simple case.
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
 * <p>The instruction order within a block is deliberately NOT reversed. Every checker in this engine
 * propagates a forward lattice -- taint labels, nullable facts, interval bounds -- so reversing a
 * block would visit uses before defs and silently break the propagation those checkers depend on.
 * The engine's summary step reads the facts the forward walk already produced rather than doing its
 * own backward use-def walk.
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
   * Per-function source path map, keyed by enclosing class. Set by the caller (e.g. ScanRunner)
   * before {@link #run} to enable filePath-filling for merged programs where the synthetic {@code
   * MergedProgram.asProgram()} has no single source path. When null, the engine falls back to
   * {@code program.sourcePath()} (which works for single-file scans).
   */
  private java.util.Map<String, java.nio.file.Path> functionSourcePaths;

  /**
   * R-810 ? the {@link org.plumb.dfa.resolve.StaticFieldResolver} installed on every {@link
   * AnalysisContext} the engine builds during {@link #run}. Set by the caller (e.g. ScanRunner via
   * {@link ProjectCompilationContext#staticFields()}) before {@link #run}; {@code null} means the
   * engine skips the const-static-field resolution branch and FIELDLOAD falls back to the
   * orphan-SOURCE shape (same behaviour as pre-R-810). The field is read by every {@code new
   * AnalysisContext(...)} site at {@link #run}-time and installed via {@link
   * AnalysisContext#setStaticFieldResolver}.
   */
  private org.plumb.dfa.resolve.StaticFieldResolver staticFieldResolver;

  /**
   * R-810 ? install the {@link org.plumb.dfa.resolve.StaticFieldResolver} used by the engine.
   * Volatile so parallel workers see the published value. Idempotent.
   */
  public void setStaticFieldResolver(org.plumb.dfa.resolve.StaticFieldResolver r) {
    this.staticFieldResolver = r;
  }

  public DfaEngine(
      EngineConfig config,
      List<DfaChecker> checkers,
      String callGraphStrategyName,
      Path callGraphCachePath) {
    this(config, checkers, callGraphStrategyName, callGraphCachePath, 0);
  }

  /**
   * Full constructor. {@code parallelism} controls the worklist scheduler:
   *
   * <ul>
   *   <li>{@code 0} : sequential. Same as the 4-arg constructor.
   *   <li>{@code >0} : parallel. The engine spins up an {@link ExecutorService} with this many
   *       worker threads and runs a worklist over the call graph: as soon as a function's callees
   *       are all summarised, it joins the ready queue.
   * </ul>
   *
   * Negative values are clamped to 0 (sequential).
   */
  public DfaEngine(
      EngineConfig config,
      List<DfaChecker> checkers,
      String callGraphStrategyName,
      Path callGraphCachePath,
      int parallelism) {
    this.config = Objects.requireNonNull(config, "config");
    this.checkers = List.copyOf(Objects.requireNonNull(checkers, "checkers"));
    this.callGraphStrategyName = callGraphStrategyName == null ? "global" : callGraphStrategyName;
    this.callGraphCachePath = callGraphCachePath;
    this.parallelism = Math.max(0, parallelism);
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
   */
  public DfaEngine withFunctionSourcePaths(java.util.Map<String, java.nio.file.Path> paths) {
    this.functionSourcePaths = paths;
    // R-810: lazily build a
    // {@link StaticFieldResolver} keyed off the
    // first source path. The resolver walks the
    // project root and indexes every
    // {@code public static final Foo = <literal>}
    // field so FIELDLOAD on a class reference
    // (Juliet's {@code Level.WARNING},
    // {@code IO.STATIC_FIVE}) can attach a real
    // parent instead of falling through to the
    // orphan-SOURCE shape.
    // Best-effort: if the static-field walk
    // throws (rare — only on filesystem
    // permission failures), the resolver stays
    // null and FIELDLOADs fall back to the
    // pre-R-810 orphan behaviour.
    if (this.staticFieldResolver == null && paths != null && !paths.isEmpty()) {
      java.nio.file.Path sample = null;
      for (java.nio.file.Path p : paths.values()) {
        if (p != null) {
          sample = p;
          break;
        }
      }
      if (sample != null) {
        java.nio.file.Path abs = sample.toAbsolutePath();
        java.nio.file.Path root = chooseResolverRoot(abs);
        if (root != null) {
          try {
            SourcePathResolver spr = new SourcePathResolver(root);
            spr.index();
            StaticFieldResolver sfr = new StaticFieldResolver(spr);
            sfr.index();
            this.staticFieldResolver = sfr;
          } catch (Exception ignore) {
            // Leave staticFieldResolver null;
            // FIELDLOADs fall through to the
            // orphan-SOURCE shape (pre-R-810
            // behaviour). The exception is
            // swallowed because resolver
            // failure is non-fatal — every
            // FIELDLOAD just gets a
            // parent-less SOURCE node and the
            // analysis continues.
          }
        }
      }
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
   * R-810 ? pick the narrowest root the StaticFieldResolver can walk without timing out. Walks up
   * from the input file's directory and prefers {@code <ancestor>/src/testcasesupport} (Juliet's IO
   * + helper root), then {@code <ancestor>/src} (Juliet's full tree, capped), then the file's own
   * parent (single-file unit-test case). Returns {@code null} if no usable root can be found below
   * the safety budget.
   */
  private static java.nio.file.Path chooseResolverRoot(java.nio.file.Path absSample) {
    if (absSample == null) return null;
    // 1. Walk up to the closest
    // ancestor that has both a
    // `testcases/` AND a
    // `testcasesupport/` child —
    // the Juliet layout. The
    // resolver wants
    // testcasesupport/ (small,
    // contains IO.java + Helper.java,
    // <100 files). For
    // CWE89_*/s01/CWE89_*.java the
    // walk reaches
    // D:/work/tmp/Juliet_Java/src.
    // Limit the walk to 8 levels to
    // stay cheap on accidentally
    // nested paths.
    java.nio.file.Path cur = absSample.getParent();
    for (int i = 0; i < 8 && cur != null; i++) {
      boolean hasTestcases = java.nio.file.Files.isDirectory(cur.resolve("testcases"));
      boolean hasSupport = java.nio.file.Files.isDirectory(cur.resolve("testcasesupport"));
      if (hasTestcases && hasSupport) {
        return cur.resolve("testcasesupport");
      }
      cur = cur.getParent();
    }
    // 2. Fallback — walk up 2
    // levels and use the package
    // root. For unit-test
    // single-file cases (no
    // siblings), this gives the
    // resolver just the package
    // contents (a handful of
    // files), keeping the walk
    // bounded.
    if (absSample.getParent() != null && absSample.getParent().getParent() != null) {
      return absSample.getParent().getParent();
    }
    if (absSample.getParent() != null) {
      return absSample.getParent();
    }
    return null;
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
    AnalysisContext ctx = new AnalysisContext(programView, cg, config, reporter);
    ctx.setStaticFieldResolver(staticFieldResolver);
    reporter.setContext(ctx);
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
    for (DfaChecker c : checkers) {
      if (c instanceof org.plumb.dfa.taint.TaintedCheckerBase tcb && tcb.solverBridge() != null) {
        ctx.guardStore().setBridge(tcb.solverBridge());
        break;
      }
    }
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
        // R716: qualified-alias put. Juliet's cross-TU
        // scan goes through {@code runWithGlobalStore}
        // (this code path). Without this helper call the
        // bare {@code method/arity} alias collides across
        // the 60+ Variant 42/45 files that share
        // {@code badSource/0} and last-write-wins returns
        // the wrong Variant's summary.
        r716putQualifiedAliasSummary(ctx, fn, merged2);
      }
    }
    // R717: drain any functions that need re-analysis because
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
    return reporter;
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
    // Build the dep map from the cross-TU call graph. The
    // standard CallGraph already has forward+reverse maps;
    // we just need to count "in-TU callees" per function.
    java.util.concurrent.ConcurrentMap<String, AtomicInteger> deps =
        new java.util.concurrent.ConcurrentHashMap<>();
    for (TacFunction fn : programView.functions()) {
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
      deps.put(fn.signature(), new AtomicInteger(calleeCount));
    }
    // Reverse caller map
    java.util.Map<String, java.util.List<String>> callersOf = new java.util.HashMap<>();
    for (TacFunction fn : programView.functions()) {
      org.plumb.dfa.cg.FunctionNode node = new org.plumb.dfa.cg.FunctionNode(fn.signature());
      for (var cs : cg.callsFrom(node)) {
        String calleeSig = cs.callee().signature();
        if (calleeSig.equals(fn.signature())) continue;
        if (store.functionBySignature(calleeSig).isEmpty()) continue;
        callersOf.computeIfAbsent(calleeSig, k -> new java.util.ArrayList<>()).add(fn.signature());
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
    try {
      while (true) {
        String polled;
        while ((polled = readyQ.poll()) != null) {
          final String sig = polled;
          phaser.register();
          exec.submit(
              () -> {
                try {
                  TacFunction fn = programView.function(sig);
                  if (fn != null && analysedOrigins.contains(fn.origin())) {
                    ctx.factBag().clear();
                    ctx.sideEffectConsumer().clear();
                    analyseOneFunction(ctx, fn);
                    FunctionSummary m = summariseOneFunction(ctx, fn);
                    if (!m.isTrivial() || m.hasBody()) {
                      ctx.summaryCache().put(sig, m);
                    }
                    // R716: qualified-alias put
                    // for the parallel global worklist.
                    // ConcurrentHashMap.put is
                    // last-writer-wins per key, but
                    // different functions in different
                    // files have different enclosing
                    // classes and thus different
                    // qualified keys : no race.
                    r716putQualifiedAliasSummary(ctx, fn, m);
                  }
                  java.util.List<String> cs = callersOf.get(sig);
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
   * R1099: compute, for every function and parameter index, the taint ORIGIN its callers pass in,
   * and publish it on {@link AnalysisContext#setParamOrigins(java.util.Map)}.
   *
   * <p>Why this has to be a pre-pass: functions are analysed in reverse post-order, so a callee is
   * analysed before ANY of its callers. When a sink inside the callee emits a finding, the
   * caller's {@link FunctionSummary} does not exist yet — so the callee can only report its own
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
   * R1106: solve the program's alias map once, before any checker runs.
   *
   * <p>Points-to is a whole-program property: a fact learned in one function routinely changes the
   * answer in another, so a per-function solve would be both wrong and more expensive. Solving once
   * up front means every checker reads the same fixed point, which is the point of putting it on the
   * {@link AnalysisContext} rather than inside one rule.
   *
   * <p>Failures are contained. The solver is a static analysis like any other, and a source tree it
   * cannot handle (an unsupported construct, a namespace it cannot reconcile) must degrade to "no
   * alias information" rather than take the scan down with it -- every consumer treats an absent
   * answer as "unknown", never as "aliases".
   */
  private void solvePointsTo(AnalysisContext ctx, TacProgram program) {
    try {
      // R1107: the policy is a configuration knob, not a hard-coded choice. The default is
      // CALL_SITE (1-CFA) because context insensitivity merges two invocations of the same
      // method into one formal and one return set, and every consumer of this map inherits
      // that merge -- see PointsToSolver's class comment.
      org.plumb.dfa.points.AbstractObject.Policy policy =
          config.tuning().effectivePointsToPolicy();
      org.plumb.dfa.points.PointsToSolver solver =
          new org.plumb.dfa.points.PointsToSolver(policy);
      solver.solve(program.functions());
      ctx.setPointsToSolver(solver);
      // R1108: copy equivalence is solved in the same pass so a checker can transfer a fact from
      // one name to another even when the receiver has no points-to set (a parameter, typically).
      ctx.setCopyEquivalence(
          org.plumb.dfa.points.CopyEquivalence.of(program.functions()));
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
        // R1109: parameters every observed call site supplies with a value the analysis can
        // positively prove clean. See setParamCleanliness for the contract.
        HashSet<String> cleanArgs = new HashSet<>();
        HashSet<String> dirtyArgs = new HashSet<>();

        // --- pass 1: intra-procedural label flow, per function -------------------
        // localLabel[fn][varName] is the origin that var currently carries inside fn.
        HashMap<String, HashMap<String, String>> localLabel = new HashMap<>();

        for (TacFunction fn : program.functions()) {
            HashMap<String, String> lab = new HashMap<>();
            // Entry boundary: every parameter is a trust boundary in its own right.
            for (int i = 0; i < fn.params().size(); i++) {
                String name = fn.params().get(i).name();
                lab.put(name, "param:" + name);
                origin.put(key(fn, i), "param:" + name);
            }
            localLabel.put(fn.signature(), lab);
        }

        // Iterate: local flow within a function, plus transfer across call edges, to a
        // fixpoint. Bounded so it provably terminates.
        // Index program functions by bare "method/arity" so a CALL's calleeSig can be
        // resolved without depending on the call graph's namespace.
        java.util.Map<String, List<TacFunction>> byBare = new java.util.HashMap<>();
        for (TacFunction fn : program.functions()) {
            byBare.computeIfAbsent(nameArity(fn.signature()), k -> new ArrayList<>()).add(fn);
        }

        int maxRounds = Math.max(4, program.functions().size() + 2);
        HashMap<String, String> retLabel = new HashMap<>();

        for (int round = 0; round < maxRounds; round++) {
            boolean changed = false;

            // (a) intra-procedural propagation over each function's instructions
            for (TacFunction fn : program.functions()) {
                HashMap<String, String> lab = localLabel.get(fn.signature());
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
                                    && org.plumb.dfa.taint.InputSourcePatterns.isInputSourceCall(
                                            ins.calleeSig)) {
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
            // R1099: edges come from the TAC CALL instructions, NOT from the call graph.
            // The global call graph namespaces a node as "Class :: signature" while the
            // merged TacProgram uses the bare signature, so matching them by equality
            // silently yields zero edges -- which is exactly the bug this replaced.
            for (TacFunction caller : program.functions()) {
                HashMap<String, String> lab = localLabel.get(caller.signature());
                if (lab == null) continue;
                for (TacInstruction call : caller.instructions()) {
                    if (call.kind != TacInstruction.Kind.CALL) continue;
                    TacFunction callee = resolveCallee(program, byBare, call.calleeSig);
                    if (callee == null) continue;
                    List<org.plumb.tac.ir.TacOperand> args = call.args;
                    for (int j = 0; j < callee.params().size(); j++) {
                        int argIdx = j + 1; // args[0] is the receiver slot
                        if (argIdx >= args.size()) break;
                        String k = key(callee, j);
                        // R1109: was this argument a literal? Only a literal is positive proof
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
                        // R1099: the entry-boundary seed (`param:<own name>`) is a PLACEHOLDER,
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
        // R1109: only parameters with at least one call site and no non-literal call site count
        // as clean. A parameter nobody calls keeps "unknown", which preserves the historical
        // behaviour where a method analysed as an entry point still treats its parameters as
        // trust boundaries.
        java.util.Map<String, Boolean> clean = new java.util.LinkedHashMap<>();
        for (String k : cleanArgs) {
            if (!dirtyArgs.contains(k)) clean.put(k, Boolean.TRUE);
        }
        ctx.setParamCleanliness(java.util.Collections.unmodifiableMap(clean));
/* R1099 diag removed */
    }

    /** R1099: name of the variable an instruction writes, or {@code null}. */
    private static String destVarName(TacInstruction ins) {
        if (ins == null) return null;
        return (ins.dest instanceof org.plumb.tac.ir.TacOperand.TacVar d) ? d.name() : null;
    }

    /**
     * R1099: the first non-null label among the instruction's operands, or {@code null} when
     * none of them carries taint. Used for both dest-inherits-operand and RETURN.
     */
    private static String labelOfAnyArg(TacInstruction ins, HashMap<String, String> lab) {
        if (ins == null || lab == null) return null;
        for (org.plumb.tac.ir.TacOperand op : ins.args) {
            if (op instanceof org.plumb.tac.ir.TacOperand.TacVar v) {
                String l = lab.get(v.name());
                if (l != null) return l;
            }
        }
        return null;
    }

    /**
     * R1099: resolve a CALL's {@code calleeSig} (e.g. {@code "SourceLayer.readRequestParam/1"})
     * to the function in the program whose declared signature is
     * {@code "readRequestParam(String):String"}. The two use different naming schemes, so we
     * reduce both to {@code name/arity} and then disambiguate.
     *
     * <p>When the {@code name/arity} key has more than one candidate (overloads, or the same
     * method name in different classes of a merged program) we prefer a candidate in the class
     * named by the call's qualifier, then a unique candidate. If neither is decisive we return
     * {@code null}: attributing a finding to an arbitrary overload would be worse than keeping
     * the honest {@code param:} label.
     */
    private static TacFunction resolveCallee(
            TacProgram program,
            java.util.Map<String, List<TacFunction>> byBare,
            String calleeSig) {
        if (calleeSig == null || byBare == null) return null;
        TacFunction exact = program.function(calleeSig);
        if (exact != null) return exact;
        List<TacFunction> cands = byBare.get(nameArityOfCall(calleeSig));
        if (cands == null || cands.isEmpty()) return null;
        if (cands.size() == 1) return cands.get(0);
        String qualifier = qualifierOf(calleeSig);
        if (qualifier != null && !qualifier.isEmpty()) {
            TacFunction hit = null;
            for (TacFunction c : cands) {
                String enc = c.enclosingClass();
                if (enc != null && enc.equals(qualifier)) {
                    if (hit != null) return null; // ambiguous within the same class
                    hit = c;
                }
            }
            if (hit != null) return hit;
        }
        return null;
    }

    /** R1099: {@code "SourceLayer.readRequestParam/1"} -> {@code "readRequestParam/1"}. */
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

    /** R1099: {@code "SourceLayer.readRequestParam/1"} -> {@code "SourceLayer"}. */
    private static String qualifierOf(String calleeSig) {
        if (calleeSig == null) return null;
        String s = calleeSig;
        int sep = s.indexOf(" :: ");
        if (sep >= 0) s = s.substring(sep + 4);
        int slash = s.indexOf('/');
        String head = slash < 0 ? s : s.substring(0, slash);
        int dot = head.lastIndexOf('.');
        return dot > 0 ? head.substring(dot + 1) : null;
    }

    /**
     * R1099: {@code "readRequestParam(String):String"} -> {@code "readRequestParam/1"}. Arity is
     * the number of top-level commas in the parameter list, so a generic parameter containing a
     * comma ({@code Map<String,Integer>}) is counted as one parameter, not two.
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

    /** R1099: index of the ')' matching the '(' at {@code open}, or -1. */
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
     * R1099: the entry-boundary placeholder for a parameter — the label every function starts
     * with before any caller has contributed a real origin. Used to tell "nothing propagated yet"
     * apart from "a real origin was already established", so a propagated origin can overwrite
     * the placeholder without being mistaken for a conflicting second source.
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
        CallGraphStrategy strategy = CallGraphStrategyFactory.create((String)this.callGraphStrategyName, (TacProgram)program, (Path)this.callGraphCachePath);
        LOG.info("Building call graph via {} (parallel mode, {} workers)...", (Object)strategy.name(), (Object)threads);
        CallGraph cg = strategy.build();
        AnalysisContext ctx = new AnalysisContext(program, cg, this.config, reporter);
        ctx.setStaticFieldResolver(this.staticFieldResolver);
        reporter.setContext(ctx);
        DfaEngine.registerSourcePaths(ctx, program, this.functionSourcePaths);
        this.runWorklist(ctx, program, cg, threads);
        long ms = System.currentTimeMillis() - t0;
        LOG.info("DfaEngine run (parallel x{}): {} functions analysed, {} summarised, {} findings, {} ms", new Object[]{threads, ctx.factBag().operands().size(), ctx.summaryCache().size(), reporter.size(), ms});
        return reporter;
    }

    public FindingReporter runSequential(TacProgram program) {
        long t0 = System.currentTimeMillis();
        FindingReporter reporter = new FindingReporter();
        CallGraphStrategy strategy = CallGraphStrategyFactory.create((String)this.callGraphStrategyName, (TacProgram)program, (Path)this.callGraphCachePath);
        LOG.info("Building call graph via {}...", (Object)strategy.name());
        CallGraph cg = strategy.build();
        AnalysisContext ctx = new AnalysisContext(program, cg, this.config, reporter);
        ctx.setStaticFieldResolver(this.staticFieldResolver);
        reporter.setContext(ctx);
        DfaEngine.registerSourcePaths(ctx, program, this.functionSourcePaths);
        for (DfaChecker c : this.checkers) {
            try {
                c.initEngine(ctx);
            }
            catch (RuntimeException ex) {
                LOG.warn("Checker {} initEngine failed: {}", (Object)c.name(), (Object)ex.toString());
            }
        }
        for (DfaChecker c : this.checkers) {
            TaintedCheckerBase tcb;
            if (!(c instanceof TaintedCheckerBase) || (tcb = (TaintedCheckerBase)c).solverBridge() == null) continue;
            ctx.guardStore().setBridge(tcb.solverBridge());
            break;
        }
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
            }
            catch (RuntimeException ex) {
                LOG.warn("Checker {} finish failed: {}", (Object)c.name(), (Object)ex.toString());
            }
        }
        long ms = System.currentTimeMillis() - t0;
        LOG.info("DfaEngine run: {} functions analysed (of {}), {} summarised, {} findings, {} ms", new Object[]{fnCount, program.functions().size(), summaryCount, reporter.size(), ms});
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
            }
            catch (RuntimeException ex) {
                LOG.warn("Checker {} initEngine failed: {}", (Object)c.name(), (Object)ex.toString());
            }
        }
        for (DfaChecker c : this.checkers) {
            if (!(c instanceof TaintedCheckerBase)) continue;
            TaintedCheckerBase tcb = (TaintedCheckerBase) c;
            if (tcb.solverBridge() == null) continue;
            ctx.guardStore().setBridge(tcb.solverBridge());
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
            if (((AtomicInteger)e.getValue()).get() != 0) continue;
            readyQ.add((String)e.getKey());
        }
        AtomicInteger processed = new AtomicInteger(0);
        int total = deps.size();
        ExecutorService exec = Executors.newFixedThreadPool(threads, DfaEngine.namedThreadFactory("plumb-dfa-worker"));
        Phaser phaser = new Phaser(1);
        try {
            while (true) {
                String polled;
                if ((polled = (String)readyQ.poll()) != null) {
                    String sig = polled;
                    phaser.register();
                    exec.submit(() -> {
                        try {
                            List cs;
                            TacFunction fn = program.function(sig);
                            if (fn != null && analysedOrigins.contains(fn.origin())) {
                                ctx.factBag().clear();
                                ctx.sideEffectConsumer().clear();
                                this.analyseOneFunction(ctx, fn);
                                FunctionSummary merged = this.summariseOneFunction(ctx, fn);
                                if (!merged.isTrivial() || merged.hasBody()) {
                                    ctx.summaryCache().put(sig, merged);
                                }
                                this.r716putQualifiedAliasSummary(ctx, fn, merged);
                            }
                            List<String> callerSigs = callersOf.get(sig);
                            if (callerSigs != null) {
                                for (String callerSig : callerSigs) {
                                    AtomicInteger c = (AtomicInteger)deps.get(callerSig);
                                    if (c == null || c.decrementAndGet() != 0) continue;
                                    readyQ.add(callerSig);
                                }
                            }
                        }
                        catch (RuntimeException ex) {
                            LOG.warn("Parallel analysis of {} failed: {}", (Object)sig, (Object)ex.toString());
                        }
                        finally {
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
        }
        finally {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(60L, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            }
            catch (InterruptedException ie) {
                exec.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        for (DfaChecker c : this.checkers) {
            try {
                c.finish(ctx);
            }
            catch (RuntimeException ex) {
                LOG.warn("Checker {} finish failed: {}", (Object)c.name(), (Object)ex.toString());
            }
        }
        LOG.info("DfaEngine parallel worklist: {} of {} functions processed", (Object)processed.get(), (Object)total);
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
        ctx.setCurrentFunction(fn);
        try {
            for (DfaChecker c : this.checkers) {
                if (!DfaEngine.isEnabled(c.config(ctx))) continue;
                try {
                    c.initFunction(ctx, fn);
                    List<Integer> order = DfaEngine.bbRpoOrder(fn);
                    for (int idx : order) {
                        TacInstruction ins = fn.instructionAt(idx);
                        if (ins.kind == TacInstruction.Kind.CALL) {
                            try {
                                SummaryApplier.apply(ins, ctx, ins.args);
                                ctx.sideEffectConsumer().recordCall(ins, ctx);
                            }
                            catch (RuntimeException re) {
                                LOG.warn("SummaryApplier failed on {}: {}", (Object)ins.calleeSig, (Object)re.toString());
                            }
                        }
                        c.checkInstruction(ins, fn, ctx);
                    }
                    c.finishFunction(ctx, fn);
                }
                catch (RuntimeException ex) {
                    LOG.warn("Checker {} failed on {}: {}", new Object[]{c.name(), fn.signature(), ex.toString()});
                }
            }
        }
        finally {
            ctx.setCurrentFunction(null);
        }
    }

    private static void registerSourcePaths(AnalysisContext ctx, TacProgram program, Map<String, Path> functionSourcePaths) {
        Path singlePath;
        if (functionSourcePaths != null && !functionSourcePaths.isEmpty()) {
            for (Map.Entry<String, Path> e : functionSourcePaths.entrySet()) {
                ctx.registerSourcePath(e.getKey(), e.getValue());
            }
        }
        if ((singlePath = program.sourcePath()) != null) {
            for (TacFunction fn : program.functions()) {
                String enc = fn.enclosingClass();
                if (enc == null || enc.isEmpty()) continue;
                ctx.registerSourcePath(enc, singlePath);
            }
            return;
        }
        if (functionSourcePaths == null || functionSourcePaths.isEmpty()) {
            LOG.warn("TacProgram.sourcePath() is null and no functionSourcePaths map was set; new DFA checker findings will report filePath='unknown'");
        }
    }

    private FunctionSummary summariseOneFunction(AnalysisContext ctx, TacFunction fn) {
        ctx.setCurrentFunction(fn);
        FunctionSummary merged = FunctionSummary.empty();
        for (DfaChecker c : this.checkers) {
            FunctionSummary s;
            if (!DfaEngine.isEnabled(c.config(ctx))) continue;
            try {
                s = c.summarizeFunction(ctx, fn);
            }
            catch (RuntimeException ex) {
                LOG.warn("Checker {} summarizeFunction failed on {}: {}", new Object[]{c.name(), fn.signature(), ex.toString()});
                s = FunctionSummary.empty();
            }
            if (s == null) {
                s = FunctionSummary.empty();
            }
            merged = merged.merge(s);
        }
        return merged;
    }

    private void buildAndStoreMethodSummary(AnalysisContext ctx, TacFunction fn) {
        try {
            FunctionSignature sig = DfaEngine.parseFnSignature(fn);
            ctx.methodSummaryCache().put(sig, fn);
        }
        catch (RuntimeException ex) {
            LOG.warn("MethodSummaryBuilder failed on {}: {}", (Object)fn.signature(), (Object)ex.toString());
        }
    }

    private static FunctionSignature parseFnSignature(TacFunction fn) {
        String s = fn.signature();
        String name = fn.name();
        if (s == null || s.isEmpty()) {
            return new FunctionSignature("", "<unknown>", name == null ? "<unknown>" : name, List.of(), "?");
        }
        FunctionSignature parsed = null;
        try {
            parsed = FunctionSignature.parse((String)s);
        }
        catch (RuntimeException runtimeException) {
            // empty catch block
        }
        if (parsed != null) {
            return parsed;
        }
        return new FunctionSignature("", "<unknown>", name == null ? "<unknown>" : name, List.of(), "?");
    }

    public static List<Integer> bbRpoOrder(TacFunction fn) {
        Object succs;
        Objects.requireNonNull(fn, "fn");
        TacCfg cfg = TacCfgBuilder.build((TacFunction)fn);
        if (cfg.blocks().isEmpty()) {
            return List.of();
        }
        ArrayList<Integer> postOrder = new ArrayList<Integer>();
        HashSet<Integer> visited = new HashSet<Integer>();
        ArrayDeque<int[]> stack = new ArrayDeque<int[]>();
        stack.push(new int[]{cfg.entryId(), 0});
        while (!stack.isEmpty()) {
            int[] top = (int[])stack.peek();
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
                stack.push(new int[]{nextSucc, 0});
                continue;
            }
            stack.pop();
            postOrder.add(blockId);
        }
        ArrayList<Integer> rpoBlocks = new ArrayList<Integer>(postOrder.size());
        for (int i = postOrder.size() - 1; i >= 0; --i) {
            rpoBlocks.add((Integer)postOrder.get(i));
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

    private void r716putQualifiedAliasSummary(AnalysisContext ctx, TacFunction fn, FunctionSummary merged) {
        Object alias = DfaEngine.r40AliasForFunction(fn);
        if (alias == null || ((String)alias).equals(fn.signature())) {
            return;
        }
        String enclosing = fn.enclosingClass();
        if (enclosing != null && !enclosing.isEmpty()) {
            alias = enclosing + " :: " + (String)alias;
        }
        if (merged.hasBody()) {
            ctx.summaryCache().put((String)alias, merged);
            return;
        }
        FunctionSummary.Builder aliasBuilder = new FunctionSummary.Builder((String)alias);
        for (Map.Entry entry : merged.paramOps().entrySet()) {
            for (org.plumb.dfa.summary.Operation op : (Set<org.plumb.dfa.summary.Operation>) entry.getValue()) {
                aliasBuilder.recordParamOp(((Integer)entry.getKey()).intValue(), op);
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
        ctx.summaryCache().put((String)alias, aliasBuilder.build());
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

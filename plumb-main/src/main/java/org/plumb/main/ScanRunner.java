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
package org.plumb.main;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.plumb.parser.JavaModule;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.reporter.FindingFormatter;
import org.plumb.reporter.NdjsonEmitter;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstCheckerRegistry;
import org.plumb.style.StyleContext;
import org.plumb.tac.bytecode.BytecodeAnalyzer;
import org.plumb.tac.ir.TacProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrate one scan. The pipeline per file:
 *
 * <pre>
 *
 *   .java  ->  JavaSourceParser  ->  ParsedSource
 *
 *                                     |
 *
 *                                     +->  TacTranslator   ->  TacProgram
 *
 *                                     |                         |
 *
 *                                     |                         +->  DfaEngine (taint, div-by-zero, ...)
 *
 *                                     |
 *
 *                                     +->  StyleChecks       ->  StyleContext
 *
 * </pre>
 *
 * <p>Findings are aggregated across all files and emitted in the configured format. The runner is
 *
 * <p>single-threaded; a future R-version can add per-file parallelism by sharing a {@link
 * RuleRegistry} (which is thread-safe after construction).
 */
public final class ScanRunner {
  private static final Logger LOG = LoggerFactory.getLogger(ScanRunner.class);
  private final ScanConfig config;

  /**
   * The checkers that survived the language filter, by the CLASS they implement.
   *
   * <p>Empty means "no filter was applied" -- every checker is in scope. Populated where the
   * language filter is applied to the loaded configuration, and consulted by {@link
   * #buildDfaCheckers()} so a checker that declares only the JVM is never CONSTRUCTED in a C# run.
   *
   * <p><b>Why the construction has to be gated, not just the registry.</b> The checkers in {@code
   * checkers.yaml} are registered through the config, but the majority are wired by a boolean flag
   * and constructed directly. Narrowing the registry alone would leave those checkers running: the
   * request was that a C# scan does not LOAD the Java rules, and a checker that is constructed and
   * walks every instruction is loaded, whatever the registry says.
   *
   * <p>The key is the CLASS, because that is what identifies a checker across the two mechanisms:
   * the config names it as a class, and the engine holds an instance of it. The config's own name
   * ({@code DivideByZero}) is a registry key, while the instance's {@code name()} is its RULE id
   * ({@code dfa-taint-divide-by-zero}) -- neither of them is the class.
   */
  private final java.util.Map<String, org.plumb.dfa.config.CheckerConfig> languageScopedCheckers;

  /**
   * How many workers this scan may use, and why.
   *
   * <p>Decided once in {@link #run()} and obeyed by both the parse phase and the analysis phase.
   * Before this existed, the parse phase used the ForkJoin common pool -- as many workers as the
   * machine had processors, regardless of what the user asked for -- and the analysis phase was
   * always sequential, so {@code --max-parallelism} controlled nothing.
   */
  private ParallelismPlan plan = ParallelismPlan.decide(0, 1, -1);

  /**
   * The heap guard for this scan.
   *
   * <p>The plan decides how many workers the machine can afford; the guard notices when the heap is
   * filling up and reduces the analysis pool. A scan that reduces itself is a scan that finishes; a
   * scan that dies of an {@code OutOfMemoryError} is not.
   */
  private HeapGuard heapGuard = HeapGuard.forWorkers(1);

  /**
   * The budget report of the last analysis, or {@code null} when there was none.
   *
   * <p>It is kept so the completeness record can say that the analysis stopped early. A budget that
   * ran out is not a failure -- it is the tool doing what it was told -- but the user has to know
   * that the finding set is a lower bound.
   */
  private org.plumb.dfa.engine.AnalysisBudget.BudgetReport budgetReport;

  /**
   * The module scope of the last analysis, or {@code null} when there was none.
   *
   * <p>Kept for the same reason as the budget report: a finding whose sink was outside the selected
   * module is not reported, and that has to be visible.
   */
  private org.plumb.dfa.engine.ModuleScope moduleScopeReport;

  /**
   * The files that could not be parsed, and so were not analysed.
   *
   * <p>A file that fails to parse produces no findings, and a file that produces no findings looks
   * exactly like a clean file. The list is what tells them apart.
   *
   * <p>It is written from the parse workers as well as from the caller's thread, so it is a
   * synchronized list. An {@link ArrayList} written by several threads at once can lose entries and
   * end up reporting a size that does not match its contents -- and this list is the record that
   * says which files the analysis looked at.
   */
  private final List<String> unparsedFiles =
      java.util.Collections.synchronizedList(new ArrayList<>());

  /**
   * The files that were not analysed because the machine ran out of memory.
   *
   * <p>These are kept apart from {@link #unparsedFiles} because the two are different facts. A file
   * that could not be parsed is broken; a file that ran the heap out is fine, and the scan did not
   * get to it. The report says which, because the remedy is different: one is a source fix, the
   * other is a bigger machine.
   *
   * <p>Like {@link #unparsedFiles}, it is written from the parse workers, so it is synchronized.
   */
  private final List<String> outOfMemoryFiles =
      java.util.Collections.synchronizedList(new ArrayList<>());

  /**
   * The number of files the parse phase never reached because the scan was interrupted.
   *
   * <p>They are counted rather than named, because naming them would put them in the same list as
   * the files that could not be parsed, and the two are not the same fact: these were never given a
   * chance.
   */
  private int interruptedFiles;

  /**
   * The degradation messages already reported.
   *
   * <p>It exists so that a project-level degradation -- the Go toolchain's root could not be
   * located, the C# project's compilation inputs were not captured -- is reported once instead of
   * once per file. The frontend reports it on every file, because the frontend does not know which
   * file the reader will look at.
   *
   * <p>It is written from the parse workers as well as from the caller's thread, so it is a
   * synchronized set. {@code add} has to be atomic, or a degradation would be reported twice --
   * once per worker that happened to see it.
   */
  private final Set<String> reportedDegradations =
      java.util.Collections.synchronizedSet(new LinkedHashSet<>());

  /**
   * R-output-dir: layout of the on-disk cache + report directory. {@code null} when the user did
   *
   * <p>not pass {@code --output-dir}. The cache is read / written through this layout; the report
   *
   * <p>files are emitted here too when set.
   */
  private final OutputLayout layout;

  /**
   * File count threshold above which Phase 1 (parse + translate + style) runs in parallel. Below
   *
   * <p>the threshold, sequential is faster on small projects because the ForkJoin common-pool
   *
   * <p>scheduling overhead dominates the per-file parse/translate cost (a single file's parse +
   *
   * <p>translate is ~3-10 ms; pool dispatch + join ~1-2 ms each). 800 was chosen empirically.
   */
  private static final int PARALLEL_PHASE1_THRESHOLD = 800;

  /**
   * per-scan registry slot. Set by the scan driver (the {@code scan(...)} method) before entering
   *
   * <p>the sub-pipelines that consult {@link #isRuleIdAllowed(String)}. The {@code
   * #isRuleIdAllowed(String)}
   *
   * <p>uses it for the disabled-by-default gate that lets disable a rule across every emit pathway
   *
   * <p>(style, DFA, unified). Production always sets it; smoke tests leave it {@code null} so the
   *
   * <p>legacy `def.enabled()` lookup becomes a no-op.
   */
  /**
   * The ids of every rule the engine can emit, cached.
   *
   * <p>The rule filter asks "is this bare form ambiguous?" once per rule per filter entry, and the
   *
   * <p>answer does not change during a scan. Without the cache the filter would rebuild the live
   * rule
   *
   * <p>set thousands of times per scan.
   */
  private volatile java.util.Collection<String> liveRuleIdsCache;

  private final java.util.concurrent.atomic.AtomicReference<RuleRegistry> currentRuleRegistry =
      new java.util.concurrent.atomic.AtomicReference<>();

  /**
   * per-scan cache of every static method declaration reachable through the user-supplied {@code
   * <FQCN>#<methodName>}. Built
   *
   * <p>once via {@link #buildStaticMethodCache(List)} and shared with every {@code
   * newStyleContext(...)} call. Empty when the user supplies no source/module roots ( back-compat).
   */
  private java.util.Map<String, com.github.javaparser.ast.body.MethodDeclaration> staticMethodCache;

  /**
   * The language frontends for this run, built on first use.
   *
   * <p>Lazy on purpose: the Kotlin frontend starts an IntelliJ application and the Scala frontend
   *
   * <p>builds a compiler context. A scan of pure Java code must not pay for either, so the registry
   * is
   *
   * <p>only created when a file of a non-Java language is actually encountered.
   */
  private FrontendRegistry frontendRegistry;

  public ScanRunner(ScanConfig config) {
    this.config = config;
    this.layout = (config.outputDir == null) ? null : new OutputLayout(config.outputDir);
    this.languageScopedCheckers = loadLanguageScopedCheckers();
  }

  /**
   * The checkers of the loaded catalogue, by the CLASS they implement.
   *
   * <p>Loaded here, in the constructor, because the checker list is built BEFORE the analysis phase
   * loads its configuration: a scope discovered later would arrive too late to stop the
   * construction it exists to prevent.
   *
   * <p>The map holds EVERY catalogued checker, not only the surviving ones. A checker that was
   * filtered out has to be IN the map with its scope attached: the map is what says "this checker
   * is JVM-only", and a map that only held the survivors would say nothing about the ones that were
   * dropped -- which is exactly the set that must not be built.
   */
  private java.util.Map<String, org.plumb.dfa.config.CheckerConfig> loadLanguageScopedCheckers() {
    org.plumb.dfa.config.LanguageFilter filter = languageFilter();
    if (!filter.isSelective()) return java.util.Map.of();
    try {
      org.plumb.dfa.config.EngineConfig cfg = org.plumb.dfa.config.ConfigLoader.loadFromClasspath();
      java.util.Map<String, org.plumb.dfa.config.CheckerConfig> all =
          new java.util.LinkedHashMap<>();
      for (org.plumb.dfa.config.CheckerConfig c : cfg.checkers().all()) {
        if (c.className() != null && !c.className().isBlank()) {
          all.put(c.className(), c);
        }
      }
      return java.util.Collections.unmodifiableMap(all);
    } catch (RuntimeException ex) {
      // A catalogue that cannot be read must not take the scan down: the
      // checkers then run unscoped, which is the pre-existing behaviour.
      LOG.warn("could not load the checker's language scope; every checker stays in scope", ex);
      return java.util.Map.of();
    }
  }

  public int run() throws IOException {
    config.validate();
    // The worker count is decided ONCE, before any work starts, and both
    // phases obey it: the parse phase and the analysis phase.
    //
    // It used to be decided twice, differently, and neither decision was
    // connected to the `--max-parallelism` flag: the parse phase used the
    // ForkJoin common pool (nCPU-1 workers, whatever the user asked
    // for) and the analysis phase was always sequential.
    this.plan =
        ParallelismPlan.decide(
            config.maxParallelism,
            Runtime.getRuntime().availableProcessors(),
            ParallelismPlan.maxHeapMegabytes());
    this.heapGuard = HeapGuard.forWorkers(plan.workers);
    LOG.info("parallelism: {}", plan.describe());
    if (layout != null) {
      layout.ensureDirectories();
      LOG.info("R-output-dir: cache at {}, report at {}", layout.cacheDir(), layout.reportDir());
    }
    // The DSL checker is built FIRST, before the registry.
    //
    // Why the order matters: the DSL rules are user-supplied, and a
    // user-supplied rule has to appear in the live RuleRegistry
    // for two reasons.
    //
    //   1. StyleContext.report() drops a finding whose rule is
    //      "disabled by default" AND absent from the registry
    //      (or present-but-disabled). A DSL rule that is not in
    //      the registry therefore never emits.
    //   2. The registry is what the per-finding whitelist /
    //      blacklist filter consults, so an unregistered rule
    //      cannot be selected either.
    //
    // Building it here also means the rules are compiled once
    // per scan, not once per file.
    buildDslChecker();
    RuleRegistry rules = buildRegistry();
    // stash the live registry so isRuleIdAllowed()
    // can consult def.enabled() for the disabled-by-default
    // gate. Cleared on the way out of the scan so a
    // follow-up scan with a different config doesn't
    // accidentally see the previous registry.
    RuleRegistry previousRegistry = currentRuleRegistry.getAndSet(rules);
    try {
      // build the cross-file static-method cache once
      // per scan. Empty map when no source/module roots are
      // configured — same as back-compat.
      if (staticMethodCache == null) {
        staticMethodCache = buildStaticMethodCache(buildModules());
      }
      // Cross-TU: collect all .java files first, then either
      // (a) when there is more than one source, merge their
      // TacPrograms into a single MergedProgram and run the DFA
      // engine once over the merged view (so cross-file calls
      // are followed), or (b) when there is only one source, keep
      // the original per-file behavior.
      List<Path> sourceFiles = new ArrayList<>();
      List<Path> bytecodeInputs = new ArrayList<>();
      for (Path input : config.inputs) {
        if (isBytecodeArtifact(input)) {
          // .jar / .class / .war — route to the
          // SootUp-backed BytecodeAnalyzer. The bytecode
          // path is a single "merged" scan because
          // BytecodeAnalyzer already produces one
          // TacProgram per class.
          bytecodeInputs.add(input);
          continue;
        }
        for (Path file : expandInputs(input)) {
          if (!matches(file)) continue;
          if (!FrontendRegistry.isSourceFile(file)) continue;
          // The language filter is a LOAD-TIME filter: a file whose language was not selected
          // is not even collected, so the scan does not open it, translate it, or build the
          // frontend that would have read it.
          if (!languageFilter().includes(FrontendRegistry.languageOf(file))) continue;
          sourceFiles.add(file);
        }
      }
      if (sourceFiles.isEmpty() && bytecodeInputs.isEmpty()) {
        // No inputs resolved. Either the user passed
        // -i pointing at an empty dir, or they
        // forgot -i entirely. Emit a clear stderr
        // hint so the dist isn't a black box.
        //
        // This is a LOUD failure, not a clean report. A scan that analysed
        // nothing and reported nothing is indistinguishable from a clean
        // project, and that is the one outcome a scanner must never produce
        // silently. The exit code is non-zero so a CI pipeline sees it.
        System.err.println(
            "plumb: no source files found in any input "
                + "(recognised: "
                + String.join(
                    ", ",
                    FrontendRegistry.knownExtensions().stream().map(e -> "." + e).sorted().toList())
                + "; this run analyses "
                + languageFilter().describe()
                + "). "
                + "Pass -i <path> on the command line, or add an "
                + "inputs: block to your YAML config.");
        emit(List.of());
        return 2;
      }
      // Step 7 — build the checker lists once so
      // the unified engine AND the AI dedup pass can
      // use the same instances. Each checker's
      // getAiPrompt() is consulted at dedup time to
      // get the rule-specific prompt.
      List<org.plumb.dfa.engine.DfaChecker> dfaCheckers = buildDfaCheckers();
      List<org.plumb.style.AstChecker> styleChecks = buildStyleChecks();
      List<Finding> all = new ArrayList<>();
      if (!bytecodeInputs.isEmpty()) {
        all.addAll(scanBytecode(bytecodeInputs, rules, dfaCheckers, styleChecks));
      }
      if (!sourceFiles.isEmpty()) {
        // Cross-TU is the default. When
        // only one file is requested but its
        // parent directory contains other
        // .java files (e.g. sibling helpers /
        // Variant 42 / 45 cross-TU chains),
        // promote the request to a merged
        // scan so the DFA engine can follow
        // calls across files. The user can
        // still force single-file by passing
        // -Xno-cross-tu (TODO) or by scanning
        // a directory with only one file in
        // it.
        List<Path> toScan = maybeExpandForCrossTu(sourceFiles);
        if (toScan.size() == 1) {
          all.addAll(scanFile(toScan.get(0), rules, dfaCheckers, styleChecks));
        } else {
          all.addAll(scanMerged(toScan, rules, dfaCheckers, styleChecks));
        }
      }
      // Step 7 — optional LLM dedup pass. The
      // per-rule prompts live on each checker (see
      // DfaChecker.getAiPrompt / AstChecker.getAiPrompt);
      // the runner iterates the findings, looks up the
      // checker, and asks the LLM to judge. Failures
      // leave the static findings unchanged and the
      // scan finishes normally.
      if (config.aiDedup && config.aiConn.isUsable() && !all.isEmpty()) {
        all = runAiDedup(all, dfaCheckers, styleChecks);
      }
      emit(all);
      return 0;
    } finally {
      // restore the previous registry so a
      // follow-up scan with a different config doesn't
      // accidentally see this scan's rules. The
      // previous value may itself be null (no scan
      // was running before).
      currentRuleRegistry.set(previousRegistry);
      // Release the frontends. The Kotlin frontend's Analysis API holds an
      // IntelliJ application with non-daemon threads; leaving it open keeps the
      // JVM alive after the scan, so the CLI would never exit.
      if (frontendRegistry != null) {
        try {
          frontendRegistry.close();
        } catch (RuntimeException ex) {
          LOG.debug("Frontend registry close failed: {}", ex.toString());
        }
        frontendRegistry = null;
      }
    }
  }

  /**
   * Step 7 — run the AI dedup pass over the list of findings. The rule-specific prompts live on
   *
   * <p>each checker (see {@link org.plumb.dfa.engine.DfaChecker#getAiPrompt} and {@link
   * org.plumb.style.AstChecker#getAiPrompt}); the runner looks up the checker for each finding and
   *
   * <p>asks the LLM to judge. Verdicts land on the finding as {@code ai_verdict} / {@code
   * ai_reason}.
   *
   * <p>Findings tagged FP are dropped when {@code config.dropOnAiFp} is true.
   *
   * <p>Direct-import (no reflection): plumb-ai is a hard dep of plumb-main, and the AI layer is the
   *
   * <p>single source of truth for the LLM HTTP client, Re-Act agent, and 12 tools. Static analysis
   *
   * <p>still works when {@code aiConn.isUsable()} is false (no apiKey / no model) — we just skip
   * the
   *
   * <p>pass.
   *
   * <p>When {@code config.cliAiDedupStats} is true, the runner writes a verdict-distribution +
   *
   * <p>per-rule FP-ratio report to {@code stderr} (so NDJSON on stdout stays clean).
   */
  private List<Finding> runAiDedup(
      List<Finding> findings,
      List<org.plumb.dfa.engine.DfaChecker> dfaCheckers,
      List<org.plumb.style.AstChecker> styleChecks) {
    long startNanos = System.nanoTime();
    // Build a rule-name -> prompt function. Each
    // finding's rule name is mapped to its checker
    // (or to null when the rule has no checker, in
    // which case the prompt is null = no AI dedup).
    java.util.Map<String, java.util.function.Function<Finding, String>> promptByRule =
        buildPromptByRuleMap(dfaCheckers, styleChecks);
    // the reverse map (emitted rule id -> the checker's slug), so an
    // `ai.dedupRules` entry written as a slug still matches the finding.
    java.util.Map<String, String> slugByEmittedId = buildSlugByEmittedIdMap(styleChecks);
    // Allow CLI / YAML override: a user-supplied
    // --rule-prompts-dir can replace each checker's
    // inline getAiPrompt return value.
    java.util.Map<String, String> overrides = loadRulePromptOverrides();
    org.plumb.ai.agent.LlmProvider provider =
        new org.plumb.ai.agent.OpenAiCompatibleLlmProvider(config.aiConn);
    org.plumb.ai.tools.ToolRegistry tools = buildToolRegistry();
    org.plumb.ai.agent.ReActAgent agent = new org.plumb.ai.agent.ReActAgent(provider, tools);
    // Filter: only send findings whose rule has a
    // prompt. Findings without a prompt get
    // ai_verdict="(no-rule-prompt)" and are kept.
    java.util.Map<String, String> verdictByKey = new java.util.concurrent.ConcurrentHashMap<>();
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(
            Math.max(1, config.aiMaxConcurrent),
            r -> {
              Thread t = new Thread(r, "ai-dedup");
              t.setDaemon(true);
              return t;
            });
    try {
      java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
      for (Finding f : findings) {
        String ruleName = ruleNameOf(f);
        String slug = slugByEmittedId.get(ruleName);
        String inlinePrompt =
            promptByRule.containsKey(ruleName) ? promptByRule.get(ruleName).apply(f) : null;
        // an override file is named after the checker's slug (that is
        // what the rule catalogue shows the user), but the finding carries the
        // emit id -- so try the slug too.
        String overridePrompt = overrides.get(ruleName);
        if (overridePrompt == null && slug != null) {
          overridePrompt = overrides.get(slug);
        }
        String finalPrompt = (overridePrompt != null) ? overridePrompt : inlinePrompt;
        if (finalPrompt == null) {
          // No rule-specific prompt. Fall back to the GENERIC prompt rather
          // than skipping the finding.
          //
          // Skipping was the old behaviour, and it made the AI pass report
          // almost nothing: only a couple of dozen rules ship a prompt of
          // their own, so on a real scan the overwhelming majority of
          // findings came back as UNKNOWN with the reason
          // "(no-rule-prompt)" -- which is indistinguishable from "the model
          // was unsure", and is not a verdict at all.
          //
          // The five analysis questions are rule-agnostic. "Is there really a
          // source?", "is this really a sink?", "is the trace real?", "can
          // the path conditions hold?", "is there really a sanitiser?" are
          // the same questions for a SQL sink and for a null dereference, so
          // a rule without its own prompt can still be reviewed.
          //
          // The rule-specific prompt still wins where one exists: it knows
          // things the generic prompt cannot, such as which over-fire
          // pattern is characteristic for that rule.
          finalPrompt = org.plumb.ai.prompts.FalsePositivePrompts.genericFor(ruleName);
        }
        if (!config.aiDedupRules.isEmpty() && !matchesAiDedupRules(ruleName, slug)) {
          verdictByKey.put(keyOf(f), "(not-in-aiDedupRules)");
          continue;
        }
        String prompt = finalPrompt;
        futures.add(
            pool.submit(
                () -> {
                  try {
                    String v = judgeOne(f, prompt, agent);
                    verdictByKey.put(keyOf(f), v);
                  } catch (Throwable t) {
                    LOG.warn("AI judge failed for {}: {}", keyOf(f), t.toString());
                    verdictByKey.put(keyOf(f), "UNKNOWN");
                  }
                }));
      }
      for (java.util.concurrent.Future<?> fu : futures) {
        try {
          fu.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
          LOG.warn("AI judge timed out");
        } catch (Exception ignore) {
        }
      }
    } finally {
      pool.shutdownNow();
    }
    // Build final finding list + stats
    java.util.Map<String, String> cleanVerdicts = new java.util.LinkedHashMap<>();
    for (Finding f : findings) {
      String k = keyOf(f);
      String v = verdictByKey.getOrDefault(k, "UNKNOWN");
      if (v.startsWith("(") && v.endsWith(")")) {
        // "no-rule-prompt" / "not-in-aiDedupRules" — keep as UNKNOWN for stats
        cleanVerdicts.put(k, "UNKNOWN");
      } else {
        cleanVerdicts.put(k, v);
      }
    }
    DedupStats stats = computeDedupStats(findings, cleanVerdicts, config.dropOnAiFp);
    List<Finding> out = new ArrayList<>(findings.size());
    int droppedFp = 0;
    int keptFindings = 0;
    for (Finding f : findings) {
      String v = verdictByKey.getOrDefault(keyOf(f), "UNKNOWN");
      String reason;
      String verdict;
      if (v.startsWith("(") && v.endsWith(")")) {
        // "no-rule-prompt" / "not-in-aiDedupRules" — pass through
        verdict = "UNKNOWN";
        reason = v;
      } else {
        // Split verdict and reason (format: "VERDICT|reason" or just "VERDICT")
        int pipe = v.indexOf('|');
        if (pipe < 0) {
          verdict = v;
          reason = "";
        } else {
          verdict = v.substring(0, pipe);
          reason = v.substring(pipe + 1);
        }
      }
      if (config.dropOnAiFp && "FP".equals(verdict)) {
        droppedFp++;
      } else {
        out.add(withAiVerdict(f, verdict, reason));
        keptFindings++;
      }
    }
    if (config.cliAiDedupStats) {
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
      printDedupStatsImpl(stats.withKept(keptFindings).withDropped(droppedFp), elapsedMs);
    }
    return out;
  }

  /**
   * Step 7 — run one LLM call for one finding. Returns a {@code "VERDICT|reason"} string. Failure
   *
   * <p>modes (provider null, agent max-turns) are mapped to UNKNOWN.
   */
  private String judgeOne(Finding f, String rulePrompt, org.plumb.ai.agent.ReActAgent agent) {
    String context = extractSourceContext(f, config.aiContextLines);
    // when the finding carries a v2 structured
    // Trace ( P3.1 / dual-write), inject a
    // compact one-line shape summary so the LLM sees
    // the trace structure on the first turn without
    // having to call get_dataflow_trace_with_facts.
    // The summary covers op distribution, sink ids,
    // typed-const source count, and orphan count —
    // the four signals the LLM needs most to
    // distinguish TP from FP ( lesson: const
    // sources are FP; follow-up: orphan count
    // is a Juliet-fixture signal). Bounded to
    // MAX_SUMMARY_CHARS (200) so the user turn stays
    // small regardless of trace size.
    String v2Summary = org.plumb.ai.tools.V2TraceSummarizer.summarize(f.trace());
    StringBuilder userTurnBuilder =
        new StringBuilder()
            .append(rulePrompt)
            .append("\n\n")
            .append("Finding: ")
            .append(ruleNameOf(f))
            .append(" at line ")
            .append(f.line())
            .append('\n')
            .append("File: ")
            // The model has to be told WHICH FILE. Without it the model
            // guesses a path, the read_source call fails, and the finding
            // is judged from the source window alone -- which is how a
            // guess about reachability turns into a false verdict. The
            // path is relative to the analysis root, which is the same
            // root the tools are given.
            .append(f.file() == null ? "(unknown)" : f.file())
            .append('\n')
            .append("Message: ")
            .append(f.message())
            .append('\n')
            .append("Dataflow: ")
            .append(f.dataflowHead() == null ? "(none)" : f.dataflowHead())
            .append('\n');
    if (!v2Summary.isEmpty()) {
      userTurnBuilder.append("V2 trace shape: ").append(v2Summary).append('\n');
    }
    userTurnBuilder
        .append("Source window:\n")
        .append(context.isEmpty() ? "(file unreadable)\n" : context);
    String userTurn = userTurnBuilder.toString();
    org.plumb.ai.agent.AgentTask task =
        org.plumb.ai.agent.AgentTask.fpReduction(
                org.plumb.ai.prompts.FalsePositivePrompts.FALSE_POSITIVE_REDUCTION,
                userTurn,
                config.aiConn.model(),
                config.aiTemperature)
            .withMaxTurns(config.aiMaxTurns);
    org.plumb.ai.tools.ToolContext toolCtx =
        new org.plumb.ai.tools.ToolContext(
            f, "fp-reduction", sourceRoot().toString(), null, null, null, null, null);
    org.plumb.ai.agent.AgentResult result = agent.run(task, toolCtx);
    if (result.kind() == org.plumb.ai.agent.AgentResult.Kind.FINAL_ANSWER) {
      return parseFinalVerdict(result.verdict());
    }
    if (result.kind() == org.plumb.ai.agent.AgentResult.Kind.MAX_TURNS_EXCEEDED) {
      return "UNKNOWN|max-turns-exceeded";
    }
    if (result.kind() == org.plumb.ai.agent.AgentResult.Kind.PROVIDER_ERROR) {
      return "UNKNOWN|provider-error: " + result.error();
    }
    return "UNKNOWN|tool-error: " + result.error();
  }

  /**
   * Step 7 — parse the agent's FINAL body into a single-line "{verdict}|{reason}" string. The
   *
   * <p>agent's final body is a JSON object like {@code {"verdict":"TP","reason":"..."}}. Tolerant
   * to:
   *
   * <p>missing fields, leading/trailing junk, fences.
   */
  private static String parseFinalVerdict(String body) {
    if (body == null || body.isEmpty()) return "UNKNOWN|no-reply";
    try {
      String trimmed = body.trim();
      if (trimmed.startsWith("FINAL:")) {
        trimmed = trimmed.substring("FINAL:".length()).trim();
      }
      // Strip code fences if any.
      if (trimmed.startsWith("```")) {
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline > 0) trimmed = trimmed.substring(firstNewline + 1);
        if (trimmed.endsWith("```")) {
          trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();
      }
      // Try parsing as JSON.
      com.fasterxml.jackson.databind.JsonNode n =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
      String v = n.path("verdict").asText("UNKNOWN");
      String r = n.path("reason").asText("");
      return v + "|" + r;
    } catch (Exception e) {
      return "UNKNOWN|parse-error";
    }
  }

  /**
   * Step 7 — build the tool registry used by the dedup agent. 12 tools (IR-aware + Grep fallback
   *
   * <p>Claude Code-style file ops). See {@link org.plumb.ai.tools} for each tool's docs.
   */
  private org.plumb.ai.tools.ToolRegistry buildToolRegistry() {
    return new org.plumb.ai.tools.ToolRegistry()
        .register(new org.plumb.ai.tools.ReadSourceTool())
        .register(new org.plumb.ai.tools.GrepTool())
        .register(new org.plumb.ai.tools.GlobTool())
        .register(new org.plumb.ai.tools.ListDirTool())
        .register(new org.plumb.ai.tools.SearchPatternTool())
        .register(new org.plumb.ai.tools.FindDefinitionsTool())
        .register(new org.plumb.ai.tools.FindCallersTool())
        .register(new org.plumb.ai.tools.HasSanitizerOnPathTool())
        .register(new org.plumb.ai.tools.GetMethodAnnotationsTool())
        .register(new org.plumb.ai.tools.GetClassHierarchyTool())
        .register(new org.plumb.ai.tools.GetFullClassTool())
        .register(new org.plumb.ai.tools.GetDataflowTraceWithFactsTool());
  }

  /**
   * Step 7 — build a rule-name -> prompt-producer map. The runner uses this to look up the rule
   *
   * <p>prompt for each finding by its rule id. Findings whose rule is not in the map keep their
   * {@code ai_verdict} as UNKNOWN (no AI review was performed).
   *
   * <p><b>— the map must be keyed by the id the FINDING carries, not only by the checker's
   *
   * <p>slug.</b> {@link #ruleNameOf(Finding)} returns {@code finding.rule().ruleId().id()} (the
   *
   * <p>canonical emit id, e.g. {@code ast-bug-bc-equals-no-null}), but the map used to be keyed
   * only
   *
   * <p>by {@code checker.name()} (the kebab-case slug, e.g. {@code bc-equals-no-null}). Those two
   *
   * <p>strings are equal for <em>zero</em> of the 595 AST checkers, so every AST finding fell into
   * the
   *
   * <p>{@code "(no-rule-prompt)"} branch and the AI FP-reducer never reviewed a single style rule.
   * The
   *
   * <p>DFA taint family was unaffected because {@code TaintedCheckerBase.name()} returns {@code
   * rule.ruleId()} by construction.
   *
   * <p>So each checker is registered under BOTH keys: its slug (so a user's {@code
   * --rule-prompts-dir} override and any slug-keyed lookup keep working) and the id of the rule it
   *
   * <p>actually emits (so the finding's own id finds its prompt). The emitted id is read from the
   *
   * <p>checker's own {@code rule()}, which is the same source of truth {@code
   * StyleContext.report()}
   *
   * <p>uses.
   */
  private java.util.Map<String, java.util.function.Function<Finding, String>> buildPromptByRuleMap(
      List<org.plumb.dfa.engine.DfaChecker> dfaCheckers,
      List<org.plumb.style.AstChecker> styleChecks) {
    java.util.Map<String, java.util.function.Function<Finding, String>> map =
        new java.util.HashMap<>();
    for (org.plumb.dfa.engine.DfaChecker c : dfaCheckers) {
      String name = c.name();
      map.put(name, f -> c.getAiPrompt(f));
    }
    for (org.plumb.style.AstChecker c : styleChecks) {
      String name = c.name();
      map.put(name, f -> c.getAiPrompt(f));
      // also key by the emitted rule id. Without this the map's key
      // never equals the finding's ruleId().id(), and every AST finding was
      // skipped as "(no-rule-prompt)".
      String emittedId = emittedRuleIdOf(c);
      if (emittedId != null && !emittedId.isEmpty() && !emittedId.equals(name)) {
        map.putIfAbsent(emittedId, f -> c.getAiPrompt(f));
      }
    }
    return map;
  }

  /**
   * the canonical rule id an AST checker emits, read from its own {@code rule()}.
   *
   * <p>Returns null when the checker exposes no rule metadata, or when the id is blank. Never
   *
   * <p>throws: a checker with a broken {@code rule()} must not break the whole AI pass.
   */
  private static String emittedRuleIdOf(org.plumb.style.AstChecker c) {
    try {
      org.plumb.rules.RuleDef def = c.rule();
      if (def == null || def.ruleId() == null) return null;
      String id = def.ruleId().id();
      return (id == null || id.isEmpty()) ? null : id;
    } catch (RuntimeException ex) {
      LOG.debug("Could not read rule() from {}: {}", c.getClass().getName(), ex.toString());
      return null;
    }
  }

  /**
   * map each AST checker's emitted rule id back to the checker's slug.
   *
   * <p>Used by the {@code ai.dedupRules} allowlist so a user may name a rule either way. The key is
   *
   * <p>the id the finding carries; the value is {@code checker.name()}.
   */
  private java.util.Map<String, String> buildSlugByEmittedIdMap(
      List<org.plumb.style.AstChecker> styleChecks) {
    java.util.Map<String, String> out = new java.util.HashMap<>();
    for (org.plumb.style.AstChecker c : styleChecks) {
      String id = emittedRuleIdOf(c);
      if (id != null) out.putIfAbsent(id, c.name());
    }
    return out;
  }

  /**
   * does this finding's rule appear in the {@code ai.dedupRules} allowlist?
   *
   * <p>The allowlist may name a rule either by its canonical emit id ({@code
   * ast-bug-bc-equals-no-null}) or by the checker's slug ({@code bc-equals-no-null}). Both
   *
   * <p>spellings are accepted, because the finding only carries the emit id while the slug is what
   * a
   *
   * <p>user reads in the rule catalogue and what {@code --rule-prompts-dir} names a file after.
   */
  private boolean matchesAiDedupRules(String ruleName, String slug) {
    if (config.aiDedupRules.contains(ruleName)) return true;
    return slug != null && config.aiDedupRules.contains(slug);
  }

  /**
   * Step 7 — load user-supplied rule prompt overrides from {@code --rule-prompts-dir}. Each file
   *
   * <p>{@code <rule-name>.md} replaces the corresponding checker's inline {@code getAiPrompt}
   * return
   *
   * <p>value. Files are plain Markdown; the content is passed verbatim to the LLM.
   */
  private java.util.Map<String, String> loadRulePromptOverrides() {
    java.util.Map<String, String> out = new java.util.HashMap<>();
    java.nio.file.Path dir = config.cliRulePromptsDir;
    if (dir == null || !java.nio.file.Files.isDirectory(dir)) return out;
    try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(dir)) {
      stream.forEach(
          p -> {
            String name = p.getFileName().toString();
            if (!name.endsWith(".md")) return;
            String ruleName = name.substring(0, name.length() - 3);
            try {
              out.put(
                  ruleName,
                  java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignore) {
              LOG.warn("Failed to load rule prompt override {}: {}", p, ignore.toString());
            }
          });
    } catch (Exception e) {
      LOG.warn("Failed to list rule prompts dir {}: {}", dir, e.toString());
    }
    return out;
  }

  /**
   * Step 7 — best-effort source root for the agent. Uses the first input that exists as a
   *
   * <p>directory; falls back to a synthetic path so the tools can still report "file not found"
   *
   * <p>cleanly.
   */
  private java.nio.file.Path sourceRoot() {
    for (java.nio.file.Path p : config.inputs) {
      if (java.nio.file.Files.isDirectory(p)) return p;
    }
    return java.nio.file.Path.of(".");
  }

  /**
   * Step 7 — extract the human-readable rule name from a finding for the per-rule FP ratio column.
   *
   * <p>Falls back to {@code "(no-rule)"} when the finding has no rule attached.
   *
   * <p>Returns the {@link org.plumb.rules.RuleId#name()} (the simple class-style name like {@code
   * "HardcodedCredential"}) rather than the {@code id} UUID, because the AI dedup pass keys prompts
   *
   * <p>off {@link org.plumb.dfa.engine.DfaChecker#name()} (the checker's self-reported simple
   * name).
   *
   * <p>The legacy {@code plumb.*} slug lives on the rule's {@code description} field.
   */
  private static String ruleNameOf(Finding f) {
    if (f.rule() == null || f.rule().ruleId() == null) {
      return "(no-rule)";
    }
    String id = f.rule().ruleId().id();
    if (id != null && !id.isEmpty()) return id;
    String name = f.rule().ruleId().name();
    if (name != null && !name.isEmpty()) return name;
    return "(no-rule)";
  }

  /**
   * Step 7 — build a {@link DedupStats} from the findings + the AI-dedup-returned verdict map
   *
   * <p>(keyed by {@code file:line:messageHash}). Verdicts are plain strings (TP / FP / UNKNOWN,
   * with
   *
   * <p>optional reason suffix). Verdicts are clamped to TP / FP / UNKNOWN; any other string the LLM
   *
   * <p>returns is bucketed as UNKNOWN.
   */
  static DedupStats computeDedupStats(
      List<Finding> findings, java.util.Map<String, String> verdicts, boolean dropOnAiFp) {
    java.util.EnumMap<Verdict, int[]> verdictCounts = new java.util.EnumMap<>(Verdict.class);
    for (Verdict v : Verdict.values()) verdictCounts.put(v, new int[] {0});
    java.util.LinkedHashMap<String, int[]> perRuleCounts = new java.util.LinkedHashMap<>();
    int total = findings.size();
    for (Finding f : findings) {
      String key = keyOfStatic(f);
      String v = verdicts.getOrDefault(key, "UNKNOWN");
      // verdicts can be "TP|reason" or "UNKNOWN" or "FP|reason"
      int pipe = v.indexOf('|');
      String verdict = pipe < 0 ? v : v.substring(0, pipe);
      Verdict parsed = Verdict.parse(verdict);
      verdictCounts.get(parsed)[0]++;
      String ruleName = ruleNameOf(f);
      int[] rc =
          perRuleCounts.computeIfAbsent(
              ruleName, k -> new int[] {0, 0, 0, 0}); // [TP, FP, UNKNOWN, total]
      rc[3]++;
      rc[parsed.ordinal()]++;
    }
    return new DedupStats(
        total,
        verdictCounts,
        perRuleCounts,
        0, // dropped (set by caller after the dedup pass)
        0); // kept (set by caller after the dedup pass)
  }

  private static String keyOfStatic(Finding f) {
    return keyOf(f);
  }

  private static String keyOf(Finding f) {
    return (f.file() == null ? "(no-file)" : f.file().toString())
        + ":"
        + f.line()
        + ":"
        + (f.message() == null ? 0 : f.message().hashCode());
  }

  /**
   * Write a one-shot AI dedup stats report to {@code stderr}. Layout:
   *
   * <pre>
   *
   *   AI dedup stats
   *
   *   ==============
   *
   *   Total findings:   297
   *
   *   Reviewed:         297 (100.0%)
   *
   *   Dropped (FP):     158
   *
   *   Kept:             139
   *
   *   Elapsed:          357s
   *
   *
   *
   *   Verdict distribution:
   *
   *     TP        135  (45.5%)
   *
   *     FP        158  (53.2%)
   *
   *     UNKNOWN     4  ( 1.3%)
   *
   *
   *
   *   Top 10 rules by FP ratio (FP / total):
   *
   *     HardcodedCredential       12/15  (80.0%)
   *
   *     ...
   *
   * </pre>
   *
   * Output goes to stderr so the NDJSON on stdout is still pipeline-friendly.
   */
  private static void printDedupStatsImpl(DedupStats stats, long elapsedMs) {
    java.io.PrintStream out = System.err;
    out.println();
    out.println("AI dedup stats");
    out.println("==============");
    out.println("Total findings:   " + stats.total);
    int reviewed =
        stats.verdictCounts.get(Verdict.TP)[0]
            + stats.verdictCounts.get(Verdict.FP)[0]
            + stats.verdictCounts.get(Verdict.UNKNOWN)[0];
    out.println("Reviewed:         " + reviewed + " (" + pct(reviewed, stats.total) + "%)");
    out.println("Dropped (FP):     " + stats.droppedFp);
    out.println("Kept:             " + stats.kept);
    out.println("Elapsed:          " + formatElapsed(elapsedMs));
    out.println();
    out.println("Verdict distribution:");
    // TP / FP / UNKNOWN order regardless of how often
    // each fired.
    for (Verdict v : new Verdict[] {Verdict.TP, Verdict.FP, Verdict.UNKNOWN}) {
      int n = stats.verdictCounts.get(v)[0];
      out.println(
          "  "
              + padRight(v.name(), 8)
              + "  "
              + padLeft(String.valueOf(n), 6)
              + " ("
              + padLeft(pct(n, reviewed), 5)
              + "%)");
    }
    out.println();
    out.println("Top 10 rules by FP ratio (FP / total):");
    // Sort per-rule: highest FP ratio first, tie-break
    // by absolute FP count.
    java.util.List<java.util.Map.Entry<String, int[]>> sorted =
        new java.util.ArrayList<>(stats.perRuleCounts.entrySet());
    sorted.sort(
        (a, b) -> {
          int[] av = a.getValue();
          int[] bv = b.getValue();
          double ar = av[3] == 0 ? 0.0 : (double) av[1] / av[3];
          double br = bv[3] == 0 ? 0.0 : (double) bv[1] / bv[3];
          if (br != ar) return Double.compare(br, ar);
          return Integer.compare(bv[1], av[1]);
        });
    int shown = 0;
    for (java.util.Map.Entry<String, int[]> e : sorted) {
      if (shown >= 10) break;
      String name = e.getKey();
      int[] c = e.getValue();
      if (c[1] == 0) break; // no FP left in the rest
      out.println(
          "  " + padRight(name, 30) + " " + c[1] + "/" + c[3] + " (" + pct(c[1], c[3]) + "%)");
      shown++;
    }
    out.println();
  }

  /**
   * →verdict tallies + per-rule breakdown. The {@code droppedFp} and {@code kept} are filled in by
   *
   * <p>the runner after the dedup loop (depends on {@code dropOnAiFp}). The verdict / per-rule
   * fields
   *
   * <p>are computed by {@link #computeDedupStats}.
   */
  static final class DedupStats {
    final int total;
    final java.util.EnumMap<Verdict, int[]> verdictCounts;
    final java.util.LinkedHashMap<String, int[]> perRuleCounts;
    final int droppedFp;
    final int kept;

    DedupStats(
        int total,
        java.util.EnumMap<Verdict, int[]> verdictCounts,
        java.util.LinkedHashMap<String, int[]> perRuleCounts,
        int droppedFp,
        int kept) {
      this.total = total;
      this.verdictCounts = verdictCounts;
      this.perRuleCounts = perRuleCounts;
      this.droppedFp = droppedFp;
      this.kept = kept;
    }

    DedupStats withDropped(int droppedFp) {
      return new DedupStats(total, verdictCounts, perRuleCounts, droppedFp, kept);
    }

    DedupStats withKept(int kept) {
      return new DedupStats(total, verdictCounts, perRuleCounts, droppedFp, kept);
    }
  }

  private static String pct(int n, int total) {
    if (total <= 0) return "0.0";
    return String.format(java.util.Locale.ROOT, "%.1f", 100.0 * n / total);
  }

  /**
   * Pad {@code s} on the right with spaces to width {@code w} (left-aligns the text in its column).
   */
  private static String padRight(String s, int w) {
    if (s.length() >= w) return s;
    StringBuilder sb = new StringBuilder(w);
    sb.append(s);
    for (int i = s.length(); i < w; i++) sb.append(' ');
    return sb.toString();
  }

  /**
   * Pad {@code s} on the left with spaces to width {@code w} (right-aligns the text in its column).
   */
  private static String padLeft(String s, int w) {
    if (s.length() >= w) return s;
    StringBuilder sb = new StringBuilder(w);
    for (int i = 0; i < w - s.length(); i++) sb.append(' ');
    sb.append(s);
    return sb.toString();
  }

  private static String formatElapsed(long ms) {
    if (ms < 1000) return ms + "ms";
    long s = ms / 1000;
    if (s < 60) return s + "s";
    long m = s / 60;
    long rs = s % 60;
    return m + "m" + rs + "s";
  }

  // package-private test accessors ──────────────────────────────
  // (--ai-dedup-stats unit tests use these to
  //  exercise the format helpers + printer without
  //  spinning up a real AIDedupService.)
  static String pctPublic(int n, int total) {
    return pct(n, total);
  }

  static String padRightPublic(String s, int w) {
    return padRight(s, w);
  }

  static String padLeftPublic(String s, int w) {
    return padLeft(s, w);
  }

  static String formatElapsedPublic(long ms) {
    return formatElapsed(ms);
  }

  static void printDedupStats(DedupStats stats, long elapsedMs) {
    printDedupStatsImpl(stats, elapsedMs);
  }

  /**
   * Verdict enum for --ai-dedup-stats. Clamps any unknown verdict string (e.g. a model that invents
   *
   * <p>"MAYBE") to UNKNOWN so the report doesn't blow up. Package-private so the unit tests can
   * assert
   *
   * <p>on specific buckets.
   */
  enum Verdict {
    TP,
    FP,
    UNKNOWN;

    static Verdict parse(String s) {
      if (s == null) return UNKNOWN;
      return switch (s.toUpperCase(java.util.Locale.ROOT)) {
        case "TP" -> TP;
        case "FP" -> FP;
        default -> UNKNOWN;
      };
    }
  }

  /**
   * => extract ≈N source lines around the finding's line number from the file on disk. Returns the
   *
   * <p>empty string if the file can't be read (e.g. synthetic merged-program paths); the LLM is
   * still
   *
   * <p>asked but with less context.
   */
  private static String extractSourceContext(Finding f, int contextLines) {
    if (f.file() == null || f.line() <= 0) return "";
    try {
      List<String> lines =
          java.nio.file.Files.readAllLines(f.file(), java.nio.charset.StandardCharsets.UTF_8);
      if (lines.isEmpty()) return "";
      int lo = Math.max(0, f.line() - 1 - contextLines);
      int hi = Math.min(lines.size(), f.line() + contextLines);
      StringBuilder sb = new StringBuilder();
      for (int i = lo; i < hi; i++) {
        sb.append(String.format("%4d  %s%n", i + 1, lines.get(i)));
      }
      return sb.toString();
    } catch (Exception e) {
      return "(source file unreadable: " + e.getMessage() + ")";
    }
  }

  /**
   * Return a copy of {@code f} with the AI verdict + reason attached. The Finding record is
   *
   * <p>immutable, so this builds a new instance.
   *
   * <p>WHY THIS DELEGATES TO THE RECORD'S OWN COPY METHOD. This used to rebuild the finding through
   *
   * <p>the TEN-argument {@link Finding} constructor, which hardcodes {@code sinkFunction} to
   *
   * <p>GONE, while the same finding scanned WITHOUT the AI pass kept it -- and the NDJSON emitter
   *
   * <p>only writes {@code sink_function} when it is non-null, so the field simply vanished from the
   *
   * <p>report.
   *
   * <p>That is not cosmetic. The Juliet harness pins a case to the sink callee via
   *
   * <p>{@code sink_function}, so a case that matched before the AI pass stopped matching after it.
   * A
   *
   * <p>measurement that scores the AI pass on a field the AI pass itself destroys measures the
   *
   * <p>destruction, not the judgement.
   *
   * <p>{@link Finding#withAiVerdict(String, String)} copies EVERY field -- including
   *
   * <p>{@code sinkFunction} and the v2 {@code trace} -- so the verdict is the only thing that
   * changes.
   */
  private static Finding withAiVerdict(Finding f, String verdict, String reason) {
    return f.withAiVerdict(verdict, reason);
  }

  /**
   * Test seam for {@link #withAiVerdict(Finding, String, String)}.
   *
   * <p>The method is package-private rather than public so the test can pin the behaviour without
   *
   * <p>widening the runner's API: the point of the test is that attaching a verdict changes NOTHING
   *
   * <p>but the verdict, and {@code sink_function} in particular has to survive it.
   */
  static Finding withAiVerdictForTest(Finding f, String verdict, String reason) {
    return withAiVerdict(f, verdict, reason);
  }

  /**
   * Bytecode scan: feed a list of .jar / .class / .war paths to {@link BytecodeAnalyzer} and run
   *
   * <p>the DFA engine over the resulting {@link TacProgram}s. The engine setup is the same as
   * {@link #scanMerged} — same set of checkers, same {@link AnalysisContext} shape — so existing
   * findings
   *
   * <p>look the same whether they came from a Java source scan or a bytecode scan.
   */
  private List<Finding> scanBytecode(
      List<Path> inputs,
      RuleRegistry rules,
      List<org.plumb.dfa.engine.DfaChecker> dfaCheckers,
      List<org.plumb.style.AstChecker> styleChecks) {
    List<Finding> out = new ArrayList<>();
    // Pass the entire input list to a single
    // BytecodeAnalyzer so its single SootUp View spans all
    // inputs. Cross-input call resolution then works through
    // the standard call-graph builder (a callee in input[1] is
    // resolvable from a caller in input[0]). Previously each
    // input got its own View, so cross-input calls silently
    // fell through.
    List<org.plumb.tac.ir.TacProgram> programs;
    try {
      programs = new BytecodeAnalyzer(inputs).analyze();
    } catch (Exception e) {
      LOG.warn("Bytecode analysis failure for {}: {}", inputs, e.getMessage());
      return out;
    }
    if (programs.isEmpty()) {
      LOG.info("Bytecode analysis produced no TAC programs (empty .jar? library-only?)");
      return out;
    }
    // The artifacts in `inputs` are the ones the user named on the command line, so they are the
    // code under review. The bytecode front end cannot tell them from a dependency (both arrive
    // as class files), so it marks everything it lowers as LIBRARY -- and the engine analyses a
    // LIBRARY body only when the library analysis is switched on, which it is not by default. The
    // result was that a scan of a JAR analysed nothing and reported nothing: indistinguishable
    // from a clean artifact. Re-stamping the origin here is what the user's choice of input
    // already means.
    List<org.plumb.tac.ir.TacProgram> scanned = new ArrayList<>(programs.size());
    for (org.plumb.tac.ir.TacProgram program : programs) {
      scanned.add(
          org.plumb.dfa.engine.ScannedOrigin.restampProgram(
              program, org.plumb.tac.ir.SourceOrigin.SOURCE));
    }
    org.plumb.tac.ir.MergedProgram merged = new org.plumb.tac.ir.MergedProgram(scanned);
    var programView = merged.asProgram();
    if (config.runTaint
        || config.runDivByZero
        || config.runResourceLeak
        || config.runNullDeref
        || config.runHardcodedCred
        || config.runIntOverflow
        || config.runArrayBounds
        || config.runWeakCrypto
        || config.runInsecureRandom
        || config.runPredictableSeed
        || config.runPredictableKey
        || config.runStaticApiKey
        || config.runInsecureComms
        || config.runCorsMisconfig
        || config.runInsecureCookie
        || config.runBadShift
        || config.runInfiniteLoop
        || config.runConstantCondition
        || config.runSensitiveDataLeak
        || config.runFieldHardcodedPassword
        || config.runMissingAuthorization
        || config.runMissingInputValidation
        || config.runInformationExposure
        || config.runErrorMessageInfoLeak
        || config.runCommentInfoLeak) {
      try {
        out.addAll(
            runUnifiedCheckers(merged, programView, mergedSourcePathMap(merged), dfaCheckers));
      } catch (Exception e) {
        LOG.warn("Bytecode DFA failure", e);
      }
    }
    // Style checks: not supported for bytecode (they need a
    // JavaParser CompilationUnit). Log a hint and continue.
    if (config.runStyle) {
      LOG.info("Style checks skipped: bytecode path does not run style checks.");
    }
    return out;
  }

  /**
   * True if the path points to a bytecode artifact we can drive SootUp over: {@code .jar}, {@code
   * .class}, {@code .war}, {@code .ear}, {@code .zip}.
   */
  private static boolean isBytecodeArtifact(Path p) {
    if (p == null) return false;
    String s = p.toString().toLowerCase();
    return s.endsWith(".jar")
        || s.endsWith(".class")
        || s.endsWith(".war")
        || s.endsWith(".ear")
        || s.endsWith(".zip");
  }

  /**
   * Cross-TU scan. Parses and translates each .java file to its own {@link
   * org.plumb.tac.ir.MergedProgram} so the taint analysis can follow calls across files (e.g.
   *
   * <p>{@code com.foo.A.entry -> com.bar.B.wrap -> com.baz.C.clean}). Style checks still run
   * per-file
   *
   * <p>(they don't have a cross-TU view).
   *
   * <p><b>Parallel Phase 1.</b> Parse + translate is embarrassingly parallel: each file gets its
   *
   * <p>own {@link JavaSourceParser} + {@link TacTranslator} + style registry, all of which are
   *
   * <p>stateless or per-instance. The DFA phase is sequential because cross-TU call resolution
   *
   * <p>requires a single {@link org.plumb.tac.ir.MergedProgram} and a single {@link
   * returnTaintCache} / {@code analyzing} across files). On Spring Framework 6 (~5000 .java files),
   *
   * <p>parse+translate is ~30-40% of total wall time; the parallel change reduces that to the wall
   *
   * <p>time of the longest single file (work-stealing) plus a small join overhead, yielding a
   * 1.5-2.5x
   *
   * <p>speedup on multi-core hosts.
   */
  /**
   * Parse and translate every file, on a pool sized by the plan.
   *
   * <p>The pool is a DEDICATED one, not the ForkJoin common pool. The common pool has as many
   * workers as the machine has processors and is shared with every other parallel stream in the
   * JVM, so a scan could not bound its own parallelism -- and a scan that runs alongside other
   * parallel work could be starved of threads it was promised.
   *
   * <p>The order of the result is the order of the input. The parallel version used to preserve it
   * by construction ({@code parallelStream()} keeps encounter order), and a caller may depend on
   * it, so the futures are read back in order rather than as they complete.
   *
   * <p>Nothing is dropped. A file the pool could not do -- because the heap guard refused to start
   * it, or because the worker ran out of memory -- is done afterwards by THIS thread, and a file
   * that could not be translated at all is recorded as unanalysed. The result used to lose the
   * first kind of file without a trace: the worker answered {@code null} for "deferred" and for
   * "failed", the caller could not tell the two apart, and the report said nothing about the files
   * that were never looked at.
   *
   * @param files the files to translate
   * @param rules the rule registry, for the style checks
   * @param styleChecks the style checkers, or {@code null} to skip style
   * @return the translated files, in input order, with the failures omitted
   */
  private List<TranslatedFile> translateAll(
      List<Path> files, RuleRegistry rules, List<org.plumb.style.AstChecker> styleChecks) {
    return translateAll(files, rules, styleChecks, heapGuard::mayStartMoreWork);
  }

  /**
   * Parse and translate every file, with the deferral test supplied.
   *
   * <p>The deferral test is the heap guard's "may I start more work" question. It is a parameter so
   * that a test can force the guard to refuse and check that the files it refused are still
   * analysed. The failure this exists to prevent was SILENT, and a silent failure cannot be tested
   * against a real heap.
   *
   * @param files the files to translate
   * @param rules the rule registry, for the style checks
   * @param styleChecks the style checkers, or {@code null} to skip style
   * @param mayStartMoreWork whether a new file's parse may start right now
   * @return the translated files, in input order, with the failures omitted
   */
  List<TranslatedFile> translateAll(
      List<Path> files,
      RuleRegistry rules,
      List<org.plumb.style.AstChecker> styleChecks,
      java.util.function.BooleanSupplier mayStartMoreWork) {
    int workers = Math.max(1, Math.min(plan.workers, files.size()));
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(
            workers,
            new java.util.concurrent.ThreadFactory() {
              private final java.util.concurrent.atomic.AtomicInteger n =
                  new java.util.concurrent.atomic.AtomicInteger(1);

              @Override
              public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "plumb-parse-" + n.getAndIncrement());
                // A daemon thread cannot keep the JVM alive after the
                // scan is over.
                t.setDaemon(true);
                return t;
              }
            });
    try {
      java.util.List<java.util.concurrent.Future<Phase1Outcome>> futures =
          new java.util.ArrayList<>(files.size());
      for (Path f : files) {
        futures.add(
            pool.submit(() -> translateOneForPhase1(f, rules, styleChecks, mayStartMoreWork)));
      }
      // The outcomes come back in INPUT ORDER, not as they complete: a caller may depend on
      // the order of the result.
      java.util.List<Phase1Outcome> outcomes = new java.util.ArrayList<>(files.size());
      java.util.List<Integer> deferred = new java.util.ArrayList<>();
      for (int i = 0; i < futures.size(); i++) {
        try {
          Phase1Outcome outcome = futures.get(i).get();
          outcomes.add(outcome);
          if (outcome != null && outcome.retryOnCaller()) {
            deferred.add(i);
          }
        } catch (java.util.concurrent.ExecutionException e) {
          // One file's failure must not lose the other files' results -- and it must not lose
          // THAT file either. It is recorded as unanalysed: it produced no findings, and a
          // file that produces no findings has to be distinguishable from a file that was
          // never read.
          LOG.warn(
              "parallel translate failed: {}",
              e.getCause() == null ? e.toString() : e.getCause().toString());
          outcomes.add(Phase1Outcome.failed());
          unparsedFiles.add(files.get(i).toString());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          LOG.warn("parallel translate interrupted");
          // The scan is being torn down. The files that were not read back are counted, not
          // dropped: an interrupted scan says so, it does not look complete. They are NOT
          // recorded as unparseable -- they were never given a chance.
          interruptedFiles += files.size() - i;
          for (int j = i; j < files.size(); j++) {
            outcomes.add(Phase1Outcome.failed());
          }
          break;
        }
      }
      // The files the pool refused are done HERE, on the caller's thread, one at a time, with
      // the pool idle. Doing them after the pool has drained is the point: the pool was refused
      // because the heap was full, and the pool finishing is what gives the heap back.
      if (!deferred.isEmpty()) {
        LOG.info(
            "{} file(s) met a full heap while parsing and are parsed on the main thread instead",
            deferred.size());
        for (Integer index : deferred) {
          Path f = files.get(index);
          outcomes.set(index, Phase1Outcome.done(translateOnCallerThread(f, rules, styleChecks)));
        }
      }
      java.util.List<TranslatedFile> out = new java.util.ArrayList<>(files.size());
      for (Phase1Outcome outcome : outcomes) {
        if (outcome != null && outcome.translated() != null) {
          out.add(outcome.translated());
        }
      }
      return out;
    } finally {
      pool.shutdown();
    }
  }

  /**
   * Translate one file on a pool worker.
   *
   * <p>The heap guard comes first. A parse that starts when the heap is nearly full is a parse that
   * fails, so the work is deferred to the caller when there is no headroom.
   *
   * @param file the file to translate
   * @param rules the rule registry, for the style checks
   * @param styleChecks the style checkers, or {@code null} to skip style
   * @param mayStartMoreWork whether a new parse may start right now
   * @return what happened to the file
   */
  private Phase1Outcome translateOneForPhase1(
      Path file,
      RuleRegistry rules,
      List<org.plumb.style.AstChecker> styleChecks,
      java.util.function.BooleanSupplier mayStartMoreWork) {
    if (!mayStartMoreWork.getAsBoolean()) {
      return Phase1Outcome.deferred();
    }
    try {
      TranslatedFile t =
          config.runStyle ? translateAndStyleOne(file, rules, styleChecks) : translateOne(file);
      // A null result here is a GENUINE failure, not a deferral: the translation step logged
      // it and recorded the file as unanalysed.
      return Phase1Outcome.done(t);
    } catch (OutOfMemoryError oom) {
      // An OOM in ONE file must not take the whole scan down, and it must not lose the file
      // either. The translation step catches the OOMs it can see; this one got past it, so
      // the file is retried on the caller's thread, where the rest of the pool is not
      // competing for the heap.
      LOG.warn("out of memory while parsing {}; the file will be retried", file);
      return Phase1Outcome.deferred();
    }
  }

  /**
   * Translate one file on the caller's thread.
   *
   * <p>This is the fallback for the files the pool could not do. It runs one file at a time, with
   * the pool idle, so it needs a fraction of the heap the pool needed.
   *
   * @param file the file to translate
   * @param rules the rule registry, for the style checks
   * @param styleChecks the style checkers, or {@code null} to skip style
   * @return the translated file, or {@code null} when the file could not be translated
   */
  private TranslatedFile translateOnCallerThread(
      Path file, RuleRegistry rules, List<org.plumb.style.AstChecker> styleChecks) {
    try {
      return config.runStyle ? translateAndStyleOne(file, rules, styleChecks) : translateOne(file);
    } catch (OutOfMemoryError oom) {
      LOG.warn(
          "out of memory while parsing {} on the main thread; the file was not analysed", file);
      outOfMemoryFiles.add(file.toString());
      return null;
    } catch (Throwable t) {
      LOG.warn("parse failure for {}: {}", file, t.getMessage());
      unparsedFiles.add(file.toString());
      return null;
    }
  }

  private List<Finding> scanMerged(
      List<Path> files,
      RuleRegistry rules,
      List<org.plumb.dfa.engine.DfaChecker> dfaCheckers,
      List<org.plumb.style.AstChecker> styleChecks) {
    // Parallel parse + translate + per-file style.
    // The lambda body MUST be self-contained: it creates fresh
    // JavaSourceParser / TacTranslator / StyleContext / etc. so
    // that no two threads share mutable state.
    //
    // Threshold: on small projects the ForkJoin common-pool
    // scheduling overhead exceeds the per-file parse/translate
    // cost. We only parallelize when the file count is large
    // enough that the per-file amortization wins. Empirically
    // (Spring Framework 6 = 5000 files, Tomcat = 2800, Cassandra
    // = 3000), the crossover is around 800-1000 files. Below
    // that, sequential for-loop is consistently faster.
    List<TranslatedFile> translated;
    long t0 = System.nanoTime();
    if (files.size() >= PARALLEL_PHASE1_THRESHOLD) {
      // The parse phase runs on a POOL OF ITS OWN, sized by the plan.
      //
      // It used to run on `parallelStream()`, which uses the ForkJoin
      // COMMON pool: as many workers as the machine has processors,
      // whatever `--max-parallelism` said, and shared with every other
      // parallel stream in the JVM. A user who asked for four workers
      // got sixty-four.
      translated = translateAll(files, rules, styleChecks);
    } else {
      translated = new ArrayList<>(files.size());
      for (Path f : files) {
        TranslatedFile t =
            config.runStyle ? translateAndStyleOne(f, rules, styleChecks) : translateOne(f);
        if (t != null) translated.add(t);
      }
    }
    long phase1Ns = System.nanoTime() - t0;
    LOG.info(
        "phase-1 parse+translate+style on {} of {} files in {} ms ({}{})",
        translated.size(),
        files.size(),
        phase1Ns / 1_000_000,
        files.size() >= PARALLEL_PHASE1_THRESHOLD ? "parallel" : "sequential",
        config.runStyle ? "+style" : "");
    // The heap guard gets its first look at the heap NOW, after the parse phase. The parse
    // phase is where the memory pressure starts: every file's AST, its IR and its facts
    // are alive at once.
    heapGuard.sample();
    if (translated.isEmpty()) return new ArrayList<>();
    // Re-derive the original-order files list. Style findings
    // are already in `translated`; DFA findings come from
    // running the merged program.
    List<org.plumb.tac.ir.TacProgram> programs = translated.stream().map(t -> t.program).toList();
    org.plumb.tac.ir.MergedProgram merged = new org.plumb.tac.ir.MergedProgram(programs);
    var programView = merged.asProgram();
    // DFA runs once. {@code runDfaCheckers} builds the
    // AnalysisContext, runs the enabled checkers, and returns
    // the findings list. We accumulate with style findings
    // below.
    List<Finding> dfaFindings = runDfaCheckers(merged, programView, rules, dfaCheckers);
    List<Finding> out = new ArrayList<>(dfaFindings);
    for (TranslatedFile t : translated) {
      out.addAll(t.styleFindings);
    }
    return out;
  }

  /**
   * build the {@link List<JavaModule>} used by {@link #buildParser()}. Each {@code --source-root} /
   *
   * <p>{@code --module-root} becomes one flat module; the {@code --classpath} list is shared across
   *
   * <p>all modules.
   *
   * <p>Empty input — empty list, which makes the downstream {@code JavaSourceParser} behave exactly
   *
   * <p>like the JDK-only default constructor (back-compat with ).
   */
  private List<JavaModule> buildModules() {
    List<JavaModule> modules = new ArrayList<>();
    int idx = 0;
    // Per-module BuildProject.Module entries (from a build-system adapter) take
    // precedence: each one carries its own classpath, scope, languageLevel and
    // sourceEncoding so a multi-module project correctly scopes compile vs. test.
    if (config.buildProject != null
        && config.buildProject.modules() != null
        && !config.buildProject.modules().isEmpty()) {
      for (Map.Entry<String, org.plumb.build.BuildProject.Module> e :
          config.buildProject.modules().entrySet()) {
        org.plumb.build.BuildProject.Module m = e.getValue();
        modules.add(toJavaModule(m, idx++));
      }
    }
    for (Path root : config.sourceRoots) {
      modules.add(
          JavaModule.singleRoot(
              "src" + idx++,
              root,
              config.classpathJars,
              JavaModule.Scope.COMPILE,
              config.buildProject != null ? config.buildProject.languageLevel() : null,
              config.buildProject != null ? config.buildProject.sourceEncoding() : null));
    }
    for (Path root : config.moduleRoots) {
      modules.add(
          JavaModule.singleRoot(
              "mod" + idx++,
              root,
              config.classpathJars,
              JavaModule.Scope.COMPILE,
              config.buildProject != null ? config.buildProject.languageLevel() : null,
              config.buildProject != null ? config.buildProject.sourceEncoding() : null));
    }
    return modules;
  }

  /**
   * Convert a {@link org.plumb.build.BuildProject.Module} into a {@link JavaModule} suitable for
   *
   * <p>{@link JavaSourceParser}. The per-module classpath, scope, languageLevel and sourceEncoding
   * are
   *
   * <p>propagated so multi-module scans get correctly scoped symbol resolution.
   */
  private static JavaModule toJavaModule(org.plumb.build.BuildProject.Module m, int idx) {
    JavaModule.Scope scope = JavaModule.Scope.fromString(m.scope().canonical());
    return JavaModule.singleRoot(
        m.id(),
        m.sourceRoot() != null ? m.sourceRoot() : java.nio.file.Path.of("."),
        m.classpathJars(),
        scope,
        m.languageLevel(),
        m.sourceEncoding());
  }

  /**
   * pre-parse every {@code --source-root} and {@code --module-root} once, extract the FQCN of every
   *
   * <p>top-level {@code static} method declaration, and build a {@code
   * Map<String,MethodDeclaration>}
   *
   * <p>keyed by {@code <FQCN>#<methodName>}.
   *
   * <p>The cache lets AST checkers (currently {@code NullDerefFromReturnCheck}) reason about the
   *
   * <p>nullness of a {@code data = Helper.foo()} call RHS by reading the callee body — without the
   *
   * <p>checker needing to do its own cross-file symbol resolution. Building the cache once per scan
   *
   * <p>(not per file) keeps the cost O(M) where M is the total number of methods across all
   * modules.
   *
   * <p>Empty {@code sourceRoots + moduleRoots} — empty map (back-compat with / no cross-file
   *
   * <p>indexing).
   */
  private static final int R792_CACHE_MAX_FILES = 200;

  private java.util.Map<String, com.github.javaparser.ast.body.MethodDeclaration>
      buildStaticMethodCache(List<JavaModule> modules) {
    if (modules == null || modules.isEmpty()) {
      return java.util.Map.of();
    }
    JavaSourceParser parser = buildParser();
    java.util.Map<String, com.github.javaparser.ast.body.MethodDeclaration> out =
        new java.util.HashMap<>();
    java.util.Map<String, Object> fields = new java.util.HashMap<>();
    for (JavaModule m : modules) {
      String pkgPrefix = packageNameOf(m.sourceRoot());
      for (Path src : m.sourceFiles()) {
        // The static-method cache is a JavaParser AST cache. A Kotlin or
        // Scala file has no JavaParser AST, so it contributes nothing to it.
        // Skipping it here (rather than letting the parse fail) keeps the
        // cache a Java-only structure instead of half-populated with errors.
        if (FrontendRegistry.languageOf(src) != org.plumb.tac.ir.SourceLanguage.JAVA) {
          continue;
        }
        org.plumb.parser.ParsedSource ps;
        try {
          ps = parser.parse(src);
        } catch (java.io.IOException ex) {
          LOG.debug("pre-parse failed for {}: {}", src, ex.getMessage());
          continue;
        } catch (RuntimeException ex) {
          LOG.debug("pre-parse failed for {}: {}", src, ex.getMessage());
          continue;
        }
        if (ps.unit() == null) continue;
        String filePkg = ps.packageName();
        String effectivePkg = filePkg != null && !filePkg.isEmpty() ? filePkg : pkgPrefix;
        // The type name comes from the unit's FIRST type declaration, not from
        // CompilationUnit.getPrimaryTypeName(). The latter matches the type against the FILE
        // NAME, and the parser reads the source from a String, so there is no file name and the
        // answer is empty. That put every entry under "<package>." -- e.g.
        // "testcasesupport.#writeLine"
        // instead of "testcasesupport.IO#writeLine" -- which no lookup could ever find.
        String typeName = firstTypeName(ps.unit());
        if (typeName == null || typeName.isEmpty()) continue;
        String fqcnPrefix =
            effectivePkg == null || effectivePkg.isEmpty()
                ? typeName
                : effectivePkg + "." + typeName;
        ps.unit()
            .walk(
                com.github.javaparser.ast.body.MethodDeclaration.class,
                md -> {
                  if (!md.isStatic()) return;
                  String fqcnKey = fqcnPrefix + "#" + md.getNameAsString();
                  // First-write-wins on FQCN collisions across
                  // duplicated sources. We never expect two
                  // static methods with the same FQCN in a
                  // clean module.
                  out.putIfAbsent(fqcnKey, md);
                });
        // The static fields of the same class, with their constant values. A field that holds a
        // literal and is never assigned elsewhere is a fact about the whole program, so it is
        // worth carrying across files.
        ps.unit()
            .walk(
                com.github.javaparser.ast.body.FieldDeclaration.class,
                fd -> {
                  if (!fd.isStatic()) return;
                  for (com.github.javaparser.ast.body.VariableDeclarator vd : fd.getVariables()) {
                    if (!vd.getInitializer().isPresent()) continue;
                    org.plumb.style.quality.ConstantResolver.Value v =
                        org.plumb.style.quality.ConstantResolver.literalValue(
                            vd.getInitializer().get());
                    if (v == null) continue;
                    fields.putIfAbsent(fqcnPrefix + "#" + vd.getNameAsString(), v.value());
                  }
                });
      }
    }
    this.staticFieldValues = fields;
    LOG.info(
        "static-method cache built -> {} entries across {} module(s); static field constants -> {}",
        out.size(),
        modules.size(),
        fields.size());
    return out;
  }

  /**
   * The name of the compilation unit's first type declaration.
   *
   * <p>{@link com.github.javaparser.ast.CompilationUnit#getPrimaryTypeName()} cannot be used here:
   *
   * <p>it matches the type against the FILE NAME, and the parser reads the source from a String, so
   *
   * <p>there is no file name and the answer is always empty. The first type declaration is the type
   *
   * <p>the file declares, which is what a lookup by fully-qualified name means.
   *
   * @return the type's simple name, or {@code null} when the unit declares no type.
   */
  private static String firstTypeName(com.github.javaparser.ast.CompilationUnit unit) {
    if (unit == null) return null;
    return unit.getTypes().stream()
        .findFirst()
        .map(com.github.javaparser.ast.body.TypeDeclaration::getNameAsString)
        .orElse(null);
  }

  /**
   * The constant values of the static fields of other classes, keyed by {@code <FQCN>#<fieldName>}.
   *
   * <p>Populated by {@link #buildStaticMethodCache} in the same pass that builds the method cache,
   *
   * <p>because both read the same parsed sources. The values are the same {@link Object}s the
   * {@link Long}, or a
   *
   * <p>{@link String}.
   */
  private java.util.Map<String, Object> staticFieldValues = java.util.Map.of();

  /**
   * Best-effort package name inferred from a source root's directory layout. Looks for a {@code
   * src/main/java} or {@code src/test/java} segment and treats the remainder as the package.
   *
   * <p>Returns empty string when the layout doesn't match — the caller's per-file {@code
   * ParsedSource.packageName()} takes precedence.
   */
  private static String packageNameOf(Path sourceRoot) {
    if (sourceRoot == null) return "";
    String s = sourceRoot.toString().replace('\\', '/');
    int idx = Math.max(s.lastIndexOf("/src/main/java/"), s.lastIndexOf("/src/test/java/"));
    if (idx < 0) return "";
    String tail = s.substring(idx + "/src/main/java/".length());
    return tail.replace('/', '.');
  }

  /**
   * build a {@link JavaSourceParser} whose TypeSolver is wired to every source-root / module-root /
   *
   * <p>classpath jar the user supplied. The parser is reused across all files in this scan →its
   * symbol
   *
   * <p>cache (built into JavaParser) is keyed on (FQN) so cross-file resolution within the same
   * scan
   *
   * <p>amortizes nicely.
   *
   * <p><b>Language level.</b> The level comes from the build description, via {@link
   * JavaLanguageLevel}. Before this was wired up, the level was carried from the build description
   *
   * <p>into the module and then dropped here, so a project targeting Java 21 failed to parse with
   *
   * <p>"switch patterns not supported".
   */
  private JavaSourceParser buildParser() {
    List<JavaModule> modules = buildModules();
    if (modules.isEmpty()) {
      // Fast path: no user-supplied source/classpath —
      // JDK-only default. Keeps behaviour bit-identical.
      return new JavaSourceParser();
    }
    // The module-aware constructor derives the language level from the modules.
    return new JavaSourceParser(modules);
  }

  /**
   * Per-file parse + translate step. Returns null on parse failure (logs WARN). Created fresh state
   *
   * <p>for each call so this is safe inside a {@code parallelStream()}.
   *
   * <p>When an output-dir is set and the cache read mode allows it, we read the {@link
   * org.plumb.tac.ir.TacProgram} from the on-disk cache instead of re-parsing. When the cache write
   *
   * <p>mode allows it, we persist the freshly translated program back to the cache. The original
   *
   * <p>{@code .java} file is still consulted only to recompute the cache key; we never need its
   *
   * <p>content for a cache hit.
   */
  private TranslatedFile translateOne(Path file) {
    org.plumb.tac.ir.TacProgram program = loadCachedProgram(file);
    if (program != null) {
      return new TranslatedFile(file, program, List.of());
    }
    try {
      // JAVA keeps its existing path: the parser carries the resolved
      // TypeSolver and the translator needs the AST. The other languages go
      // through the frontend SPI, which lowers straight to TAC and needs no
      // JavaParser AST at all.
      if (FrontendRegistry.languageOf(file) != org.plumb.tac.ir.SourceLanguage.JAVA) {
        return translateNonJava(file);
      }
      ParsedSource ps = buildParser().parse(file);
      program = translateJava(ps, file);
      storeCachedProgram(file, program);
      return new TranslatedFile(file, program, List.of());
    } catch (OutOfMemoryError oom) {
      // The machine ran out of memory, not the parser. The file is recorded as not
      // analysed, and it is recorded apart from a broken file, because the remedy is
      // different: this one wants a bigger machine, not a fixed source file.
      LOG.warn("out of memory while parsing {}; the file was not analysed", file);
      outOfMemoryFiles.add(file.toString());
      return null;
    } catch (Throwable e) {
      // Catch Throwable (not just Exception): see the
      // sibling catch in translateAndStyleOne for rationale.
      LOG.warn("Parse failure for {}: {}", file, e.getMessage());
      // The file is in the project and was not analysed. It is recorded so the
      // report can say so: a file that produces no findings and a file that was
      // never read look the same otherwise.
      unparsedFiles.add(file.toString());
      return null;
    }
  }

  /**
   * Translate a Java source, recording the MODULE the file belongs to.
   *
   * <p>The module is what lets a single-module analysis report this module's findings and only
   * those, while the other modules are still read for symbol resolution so the call graph keeps
   * crossing the boundary.
   *
   * @param ps the parsed source
   * @param file the file it came from, used to resolve the module
   * @return the translated program
   */
  private org.plumb.tac.ir.TacProgram translateJava(ParsedSource ps, Path file) {
    org.plumb.tac.translate.TacTranslator translator =
        new org.plumb.tac.translate.TacTranslator(ps);
    translator.setModule(moduleOfFile(file));
    return translator.translate();
  }

  /**
   * The module a source file belongs to, or {@code null} when there is no module boundary.
   *
   * <p>The module is the source root the file lives under, named after the root's directory. So
   * {@code --module-root .../service --module service} selects the files under {@code .../service}.
   *
   * <p>The LONGEST matching root wins, so a module nested inside another module's tree is
   * attributed to itself.
   */
  String moduleOfFile(Path file) {
    if (file == null) return null;
    Path absolute = file.toAbsolutePath().normalize();
    String best = null;
    int bestDepth = -1;
    for (Path root : moduleRootsForScan()) {
      if (root == null) continue;
      Path absoluteRoot = root.toAbsolutePath().normalize();
      if (!absolute.startsWith(absoluteRoot)) continue;
      int depth = absoluteRoot.getNameCount();
      if (depth <= bestDepth) continue;
      bestDepth = depth;
      Path name = absoluteRoot.getFileName();
      best = name == null ? null : name.toString();
    }
    return best;
  }

  /**
   * The source roots that define the module boundaries.
   *
   * <p>The explicit {@code --source-root} and {@code --module-root} come first: they are the user's
   * statement about where the project's modules are. The scan INPUTS come after, and only when no
   * explicit root was given -- an input directory is a place to scan, not necessarily a module.
   */
  private List<Path> moduleRootsForScan() {
    List<Path> roots = new ArrayList<>();
    for (Path root : config.sourceRoots) {
      if (root != null && !roots.contains(root)) roots.add(root);
    }
    for (Path root : config.moduleRoots) {
      if (root != null && !roots.contains(root)) roots.add(root);
    }
    if (roots.isEmpty()) {
      for (Path input : config.inputs) {
        if (input == null) continue;
        Path dir = Files.isDirectory(input) ? input : input.getParent();
        if (dir != null && !roots.contains(dir)) roots.add(dir);
      }
    }
    return roots;
  }

  /**
   * Per-file parse + translate for a non-Java source file.
   *
   * <p>The file is handed to the frontend that owns its language, which lowers it to the same TAC
   *
   * <p>the Java path produces. From here on, nothing downstream knows the file was Kotlin or Scala:
   *
   * <p>the call graph, the taint engine and the checkers all consume TAC.
   *
   * <p>Style checks are NOT run on these files. The style checkers are JavaParser AST checkers by
   *
   * <p>construction; making them understand three ASTs would mean a second style engine, and the
   *
   * <p>coverage claim would be false for every rule that has no Kotlin or Scala equivalent. The
   *
   * <p>dataflow analysis -- the part that carries the security semantics -- does run.
   *
   * @param file the source file
   * @return the translated file, or null when the frontend could not read it at all
   */
  private TranslatedFile translateNonJava(Path file) {
    FrontendRegistry registry = frontends();
    if (registry == null) {
      return null;
    }
    FrontendRegistry.ParsedSourceFile parsed = registry.parseFile(file);
    reportDegradations(parsed.problems());
    if (parsed.functions().isEmpty() && !parsed.problems().isEmpty()) {
      // The frontend could not read the file at all. Dropping it silently
      // would make "the frontend cannot read this" look exactly like "there is
      // nothing here" -- so it is recorded as unanalysed, the same way a file that
      // could not be parsed is.
      unparsedFiles.add(file.toString());
      return null;
    }
    org.plumb.tac.ir.TacProgram program = new org.plumb.tac.ir.TacProgram(file, "");
    for (org.plumb.tac.ir.TacFunction fn : parsed.functions()) {
      program.addFunction(fn);
    }
    for (org.plumb.tac.ir.TacProgram.FunctionProblem problem : parsed.problems()) {
      program.addProgramProblem(problem);
    }
    storeCachedProgram(file, program);
    return new TranslatedFile(file, program, List.of());
  }

  /**
   * Report the frontend's degradations.
   *
   * <p>EVERY degradation is reported, not only the ones that left the file empty. A file that
   * produced functions <em>and</em> a problem is the dangerous case: the functions look analysed,
   * and the problem -- which says the analysis is reasoning about a different program than the one
   * that ships -- would be the only thing that tells the two apart. A degradation that is reported
   * only when it is total is a degradation that is silently partial when it is not.
   *
   * <p>A message is reported once. A project-level degradation -- the Go toolchain's root could not
   * be located, the C# project's compilation inputs were not captured -- is reported by the
   * frontend on every file of the project, because the frontend does not know which file the reader
   * will look at. One line per file would bury the finding rather than repeat it.
   *
   * @param problems the frontend's problems for one file
   */
  private void reportDegradations(List<org.plumb.tac.ir.TacProgram.FunctionProblem> problems) {
    for (org.plumb.tac.ir.TacProgram.FunctionProblem problem : problems) {
      String key = problem.kind() + "\n" + problem.message();
      if (!reportedDegradations.add(key)) {
        continue;
      }
      LOG.warn("{} [{}] {}", problem.functionName(), problem.kind(), problem.message());
    }
  }

  /**
   * The frontend registry for this run, built on first use.
   *
   * <p>Built lazily so that a scan of pure Java code never pays for the Kotlin Analysis API's
   *
   * <p>IntelliJ application or for a Scala compiler context.
   */
  private FrontendRegistry frontends() {
    if (frontendRegistry == null) {
      try {
        frontendRegistry =
            new FrontendRegistry(
                new org.plumb.tac.frontend.java.JavaTacFrontend(buildParser()),
                frontendSourceRoots(),
                config.classpathJars,
                config.languages);
      } catch (RuntimeException | LinkageError ex) {
        LOG.warn("Could not create the frontend registry: {}", ex.toString());
        return null;
      }
    }
    return frontendRegistry;
  }

  /**
   * The source roots the frontends resolve symbols against.
   *
   * <p><b>Why the scan inputs are included.</b> A non-Java frontend resolves a Java class by
   * looking for its SOURCE. The Kotlin frontend's Analysis API searches the session's content
   * scope, and the Scala compiler searches its {@code -sourcepath}. If those roots are not the
   * directory the project is actually in, the frontend cannot see the Java sources, the symbol does
   * not resolve, and the call's owner is dropped:
   *
   * <pre>
   *   Kotlin: JavaSink.readAll(in)   -&gt;  readAll/1   (no owner)
   * </pre>
   *
   * <p>and the cross-language call-graph edge cannot be built -- so the taint never crosses the
   * language boundary, and the finding is attributed to the sink's own file.
   *
   * <p>So the roots are, in order: the user's explicit {@code --source-root} / {@code
   * --module-root} directories, then the scan INPUT directories. The inputs are the last resort,
   * not the first: an explicit root is the user's statement about where the project's sources live,
   * and it wins.
   *
   * @return the source roots, never empty
   */
  private java.util.List<java.nio.file.Path> frontendSourceRoots() {
    java.util.List<java.nio.file.Path> roots = new java.util.ArrayList<>();
    for (java.nio.file.Path root : config.sourceRoots) {
      if (root != null && !roots.contains(root)) {
        roots.add(root);
      }
    }
    for (java.nio.file.Path root : config.moduleRoots) {
      if (root != null && !roots.contains(root)) {
        roots.add(root);
      }
    }
    // The scan inputs. A file input contributes its own DIRECTORY, because that is
    // where its sibling sources live.
    for (java.nio.file.Path input : config.inputs) {
      if (input == null) continue;
      java.nio.file.Path dir = Files.isDirectory(input) ? input : input.getParent();
      if (dir != null && !roots.contains(dir)) {
        roots.add(dir);
      }
    }
    if (roots.isEmpty()) {
      roots.add(java.nio.file.Path.of("."));
    }
    return roots;
  }

  /**
   * Per-file parse + translate + style step. Style checks are per-AST-pass (no cross-file state) so
   *
   * <p>they fit naturally into the same parallel phase. Returns null on parse failure.
   *
   * <p>Same caching rules as {@link #translateOne} for the TAC step. Style findings are
   *
   * <p><em>not</em> cached because the rule set (and therefore the resulting findings) may have
   *
   * <p>changed between runs; they are always recomputed from the (cached or fresh) AST.
   */
  private TranslatedFile translateAndStyleOne(
      Path file, RuleRegistry rules, List<org.plumb.style.AstChecker> styleChecks) {
    // A non-Java file has no JavaParser AST, so there is nothing for the style
    // checkers to walk. It goes through the frontend path, which produces the
    // TAC and no style findings.
    if (FrontendRegistry.languageOf(file) != org.plumb.tac.ir.SourceLanguage.JAVA) {
      return translateNonJava(file);
    }
    org.plumb.tac.ir.TacProgram program = loadCachedProgram(file);
    ParsedSource ps = null;
    if (program != null) {
      // We still need an AST for style checks. Re-parse (cheap).
      try {
        ps = buildParser().parse(file);
      } catch (OutOfMemoryError oom) {
        // The machine ran out of memory, not the parser. The file is recorded as not
        // analysed, and it is recorded apart from a broken file, because the remedy is
        // different: this one wants a bigger machine, not a fixed source file.
        LOG.warn("out of memory while re-parsing {}; the file was not analysed", file);
        outOfMemoryFiles.add(file.toString());
        return null;
      } catch (Throwable parseFail) {
        // Catch Throwable (not just Exception): see the
        // sibling catch below for rationale.
        LOG.warn("Re-parse failure for cached TAC {}: {}", file, parseFail.getMessage());
        // The file is in the project and was not analysed, so it is recorded -- the same
        // way a fresh parse failure is.
        unparsedFiles.add(file.toString());
        return null;
      }
    } else {
      try {
        ps = buildParser().parse(file);
        program = translateJava(ps, file);
        storeCachedProgram(file, program);
      } catch (OutOfMemoryError oom) {
        LOG.warn("out of memory while parsing {}; the file was not analysed", file);
        outOfMemoryFiles.add(file.toString());
        return null;
      } catch (Throwable e) {
        // Catch Throwable (not just Exception) because
        // JavaParser occasionally throws Errors for
        // syntactically broken files (e.g. unparseable
        // string literals, unbalanced generics). We do
        // NOT want a single bad file to crash the whole
        // Juliet sweep.
        LOG.warn("Parse failure for {}: {}", file, e.getMessage());
        // The file is in the project and was not analysed. It is recorded so the
        // report can say so: a file that produces no findings and a file that was
        // never read look the same otherwise.
        unparsedFiles.add(file.toString());
        return null;
      }
    }
    List<Finding> styleFindings = runStyleChecks(ps, file, rules, styleChecks);
    return new TranslatedFile(file, program, styleFindings);
  }

  /**
   * R-output-dir: try to read the cached TAC program for {@code file}, respecting {@link
   * ScanConfig.CacheMode}. Returns null on miss, on disabled cache, or on read error.
   */
  private org.plumb.tac.ir.TacProgram loadCachedProgram(Path file) {
    if (layout == null) return null;
    if (config.cacheMode != ScanConfig.CacheMode.READ
        && config.cacheMode != ScanConfig.CacheMode.READWRITE) {
      return null;
    }
    Path cacheFile = layout.programCacheFor(file);
    if (!java.nio.file.Files.exists(cacheFile)) return null;
    try {
      return org.plumb.tac.io.IrSerializer.readProgram(cacheFile);
    } catch (Exception e) {
      LOG.warn("Cache read failure for {} ({}): re-translating", cacheFile, e.getMessage());
      return null;
    }
  }

  /**
   * R-output-dir: persist the TAC program to the on-disk cache when the mode allows writes.
   *
   * <p>Best-effort: a write failure is logged at WARN and does not abort the scan.
   */
  private void storeCachedProgram(Path file, org.plumb.tac.ir.TacProgram program) {
    if (layout == null) return;
    if (config.cacheMode != ScanConfig.CacheMode.WRITE
        && config.cacheMode != ScanConfig.CacheMode.READWRITE) {
      return;
    }
    Path cacheFile = layout.programCacheFor(file);
    try {
      org.plumb.tac.io.IrSerializer.writeProgram(program, cacheFile);
    } catch (Exception e) {
      LOG.warn("Cache write failure for {}: {}", cacheFile, e.getMessage());
    }
  }

  /**
   * phase-2 DFA. Returns the findings list (or empty on checker exception) so the caller can
   *
   * <p>combine with style findings without depending on a local AnalysisContext.
   */
  private List<Finding> runDfaCheckers(
      org.plumb.tac.ir.MergedProgram merged,
      org.plumb.tac.ir.TacProgram programView,
      RuleRegistry rules,
      List<org.plumb.dfa.engine.DfaChecker> checkers) {
    // H audit: removed -E debug comment
    // (had been a multi-line TEMP note about a
    // hang investigation — root cause was found
    // elsewhere and the temp note was left behind).
    List<Finding> out = new ArrayList<>();
    try {
      out.addAll(runUnifiedCheckers(merged, programView, mergedSourcePathMap(merged), checkers));
    } catch (Exception e) {
      LOG.warn("TAC / DFA failure (merged)", e);
    }
    return out;
  }

  /**
   * Build a per-enclosing-class source path map from a merged program. For each underlying {@link
   * org.plumb.tac.ir.TacProgram} in the merged set, we walk its functions and record the program's
   *
   * <p>source path under each function's enclosing class. The unified engine consults this map to
   * fill
   *
   * <p>in {@code filePath} on findings whose checker left it empty.
   */
  private static java.util.Map<String, java.nio.file.Path> mergedSourcePathMap(
      org.plumb.tac.ir.MergedProgram merged) {
    java.util.Map<String, java.nio.file.Path> map = new java.util.HashMap<>();
    if (merged == null) return map;
    for (org.plumb.tac.ir.TacProgram src : merged.sources()) {
      java.nio.file.Path path = src.sourcePath();
      if (path == null) continue;
      for (org.plumb.tac.ir.TacFunction fn : src.functions()) {
        String enc = fn.enclosingClass();
        if (enc != null && !enc.isEmpty()) {
          map.put(enc, path);
        }
      }
    }
    return map;
  }

  /**
   * Run the plumb-dfa engine with the 7 DFA-only checkers from this family (excluding
   *
   * <p>InsecureCrypto which lives in plumb-style as a primary AST checker with the unified engine
   *
   * <p>reserved for the const-variable enhancement →and the unified version is already plumb-dfa,
   * just
   *
   * <p>opt-in). Also runs the 2 DFA-only checkers (IteratorConcurrentModification,
   *
   * <p>CollectionSelfReference) which the original wiring missed. Returns the converted findings
   * list.
   */
  /**
   * Unified-engine entry point. The default {@code config.cgStrategy} is {@code "global"} so this
   *
   * <p>method builds a {@link org.plumb.dfa.cg.GlobalTACStore} from {@code merged.sources()} and
   * calls
   *
   * <p>{@link org.plumb.dfa.engine.DfaEngine#runWithGlobalStore} for cross-TU analysis. TU-local
   *
   * <p>strategies ({@code cha / rta / vta / 1cfa / stub}) keep the legacy {@link
   * org.plumb.dfa.engine.DfaEngine#run} path.
   */
  /**
   * Build the list of {@link org.plumb.dfa.engine.DfaChecker}s the runner uses. The list is built
   *
   * <p>once and shared between {@link #runUnifiedCheckers} (which runs them) and {@link
   * #runAiDedup}
   *
   * <p>(which calls each checker's {@code getAiPrompt(Finding)} to look up the rule prompt for the
   * AI
   *
   * <p>dedup pass).
   */
  List<org.plumb.dfa.engine.DfaChecker> buildDfaCheckers() {
    List<org.plumb.dfa.engine.DfaChecker> checkers = new ArrayList<>();
    // ---- baseline ----
    if (config.runTryCatchFinally)
      checkers.add(new org.plumb.dfa.checkers.controlflow.TryCatchFinallyChecker());
    if (config.runOffByOne) checkers.add(new org.plumb.dfa.checkers.controlflow.OffByOneChecker());
    if (config.runEqualsCalledOnNull)
      checkers.add(new org.plumb.dfa.checkers.controlflow.EqualsCalledOnNullChecker());
    if (config.runCompareWithSelf)
      checkers.add(new org.plumb.dfa.checkers.controlflow.CompareWithSelfChecker());
    if (config.runXxe) checkers.add(new org.plumb.dfa.checkers.controlflow.XxeChecker());
    // runEmptyCatchHandler removed (plumb-style EmptyCatchBlockCheck covers it)
    if (config.runIteratorConcurrentModification)
      checkers.add(new org.plumb.dfa.checkers.controlflow.IteratorConcurrentModificationChecker());
    if (config.runCollectionSelfReference)
      checkers.add(new org.plumb.dfa.checkers.controlflow.CollectionSelfReferenceChecker());
    if (config.runUncaughtExceptionPropagator)
      checkers.add(new org.plumb.dfa.checkers.controlflow.UncaughtExceptionPropagator());
    // These two are catalogued in checkers.yaml and were never built: the
    // flag was read, the checker was not added. A checker that is configured
    // but never built is the same defect as a family with no watcher -- the
    // defect class cannot produce a finding at all.
    if (config.runSingletonRace)
      checkers.add(new org.plumb.dfa.checkers.controlflow.SingletonRaceDfaChecker());
    if (config.runServletAtomicity)
      checkers.add(new org.plumb.dfa.checkers.controlflow.ServletAtomicityDfaChecker());
    // wire the 4 DFA checkers that
    // were previously orphaned (registered in checkers.yaml
    // but never added to buildDfaCheckers()).
    if (config.runOrmLoadNullCheck)
      checkers.add(new org.plumb.dfa.checkers.controlflow.OrmLoadNullCheckDfaChecker());
    if (config.runOrmLostUpdate)
      checkers.add(new org.plumb.dfa.checkers.controlflow.OrmLostUpdateDfaChecker());
    if (config.runOrmUnnecessaryGet)
      checkers.add(new org.plumb.dfa.checkers.controlflow.OrmUnnecessaryGetDfaChecker());
    if (config.runUnlockNotLocked)
      checkers.add(new org.plumb.dfa.checkers.controlflow.UnlockNotLockedChecker());
    if (config.runDeferredReleaseInLoop)
      checkers.add(new org.plumb.dfa.checkers.controlflow.DeferredReleaseInLoopChecker());
    // ServletAtomicityDfa and
    // SingletonRaceDfa are intentionally NOT registered by
    // default: plumb-style has AST equivalents
    // (ServletAtomicityCheck / SingletonRaceCheck in
    // org.plumb.style.concurrency) that cover the same
    // CWE-362 / CWE-366 with better precision. The DFA
    // checkers remain in the source tree as a reference
    // implementation; flip config.runServletAtomicity /
    // runSingletonRace to true to enable them.
    if (config.runServletAtomicity) {
      // intentionally empty: the plumb-style AST equivalent
      // handles this CWE.
    }
    if (config.runSingletonRace) {
      // intentionally empty: the plumb-style AST equivalent
      // handles this CWE.
    }
    // CORS misconfiguration is covered by the AST checker
    // org.plumb.style.security.CorsMisconfigurationCheck (CWE-942),
    // auto-registered above in buildStyleChecks() via
    // AstCheckerRegistry.builtins().
    if (config.runCorsMisconfig) {
      // intentionally empty: the plumb-style AST equivalent
      // handles this CWE.
    }
    // Missing authorization is covered by the AST checker
    // org.plumb.style.security.MissingAuthorizationCheck, auto-registered
    // above in buildStyleChecks() via AstCheckerRegistry.builtins().
    if (config.runMissingAuthorization) {
      // intentionally empty: the plumb-style AST equivalent handles this CWE.
    }
    // Unchecked return values are covered by the AST checker
    // org.plumb.style.security.UncheckedReturnCheck (CWE-252),
    // auto-registered above in buildStyleChecks() via
    // AstCheckerRegistry.builtins().
    if (config.runUncheckedReturn) {
      // intentionally empty: the plumb-style AST equivalent
      // handles this CWE.
    }
    // Weak crypto is covered by the AST checkers
    // org.plumb.style.security.InsecureCryptoCheck and
    // org.plumb.style.style.WeakCryptoCheck (CWE-327 / CWE-328 / CWE-780),
    // auto-registered above in buildStyleChecks() via
    // AstCheckerRegistry.builtins().
    if (config.runWeakCrypto) {
      // intentionally empty: the plumb-style AST equivalent handles this CWE.
    }
    // A hardcoded password in a field is covered by the AST checker
    // org.plumb.style.security.HardcodedCredentialsCheck (CWE-798 / CWE-259),
    // auto-registered above in buildStyleChecks() via
    // AstCheckerRegistry.builtins().
    if (config.runFieldHardcodedPassword) {
      // intentionally empty: the plumb-style AST equivalent handles this CWE.
    }
    // Obsolete API use is covered by the AST checker
    // org.plumb.style.security.ObsoleteApiCheck (CWE-477); the
    // (class, method, arity) whitelist pattern is reachable via
    // MethodCallExpr.
    if (config.runObsoleteFunction) {
      // intentionally empty: the plumb-style AST equivalent handles this CWE.
    }
    // Information exposure is an annotation-only structural check with no
    // MOVE/CALL dataflow; the class/method annotation channels it uses are
    // reachable from the AST. It is covered by
    // org.plumb.style.security.InformationExposureCheck, auto-registered
    // above in buildStyleChecks() via AstCheckerRegistry.builtins().
    if (config.runInformationExposure) {
      // intentionally empty: the plumb-style AST equivalent handles this CWE.
    }
    // Bulk-register the DfaCheckers. Each has a default rule id; we wire
    // them by class reference so adding new checkers
    // in the future is just a matter of dropping a
    // class into plumb-dfa/.../checkers/ and adding
    // it to this list. Each has a default rule id and
    // its own AI prompt; the per-CWE mapping is
    // declared in plumb-dfa/src/main/resources/cwe-mappings.yaml.
    checkers.add(new org.plumb.dfa.checkers.taint.HardCodedKeyChecker());
    checkers.add(new org.plumb.dfa.checkers.taint.HardCodedPasswordSinkChecker());
    // Missing crypto initialisation is a DFA checker; it stays on the DFA
    // side because the state it needs is cross-method.
    checkers.add(new org.plumb.dfa.checkers.controlflow.MissingCryptoInitChecker());
    // Uncontrolled recursion is a DFA checker: it needs the TAC IR to
    // follow the recursion across functions.
    checkers.add(new org.plumb.dfa.checkers.controlflow.DeadlockChecker());
    // Error message information leak (CWE-209) is covered by the AST
    // checker org.plumb.style.error.ErrorMessageInfoLeakCheck,
    // auto-registered above in buildStyleChecks() via
    // AstCheckerRegistry.builtins().
    if (config.runMissingInputValidation) {
      // intentionally empty: the plumb-style AST equivalent handles this CWE.
    }
    // ---- plumb-dfa taint checkers ----
    if (config.runTaint) {
      if (config.runNullDeref) checkers.add(new org.plumb.dfa.checkers.taint.NullDerefChecker());
      if (config.runIntUnderflow)
        checkers.add(new org.plumb.dfa.checkers.taint.IntUnderflowChecker());
      if (config.runIntOverflow)
        checkers.add(new org.plumb.dfa.checkers.taint.IntOverflowChecker());
      if (config.runArrayBounds) checkers.add(new org.plumb.dfa.checkers.taint.ArrayIndexChecker());
      if (config.runDivByZero) checkers.add(new org.plumb.dfa.checkers.taint.DivByZeroChecker());
      // Boundary constraint checkers
      // (range lattice + Z3). Cheap (only
      // fires on arithmetic / array ops);
      // per-CWE YAML whitelist decides
      // which actually reports.
      checkers.add(new org.plumb.dfa.checkers.boundary.OverflowConstraintChecker());
      checkers.add(new org.plumb.dfa.checkers.boundary.UnderflowConstraintChecker());
      checkers.add(new org.plumb.dfa.checkers.boundary.DivZeroConstraintChecker());
      checkers.add(new org.plumb.dfa.checkers.boundary.ArrayBoundsConstraintChecker());
      if (config.runResourceLeak)
        checkers.add(new org.plumb.dfa.checkers.taint.ResourceLeakChecker());
      if (config.runInsecureRandom)
        checkers.add(new org.plumb.dfa.checkers.taint.InsecureRandomChecker());
      if (config.runSensitiveDataLeak)
        checkers.add(new org.plumb.dfa.checkers.taint.SensitiveDataLeakChecker());
      if (config.runUncheckedLoopCondition)
        checkers.add(new org.plumb.dfa.checkers.taint.UncheckedLoopConditionChecker());
      // register the standalone DFA
      // ResourceExhaustionChecker (Thread.sleep +
      // for-loop bound). Pre- this checker
      // existed but was never wired into the
      // scan pipeline, so CWE400 coverage was 0.
      if (config.runResourceExhaustion)
        checkers.add(new org.plumb.dfa.checkers.taint.ResourceExhaustionChecker());
      if (config.runUncontrolledMemAlloc)
        checkers.add(new org.plumb.dfa.checkers.taint.UncontrolledMemAllocChecker());
      if (config.runNumericTruncation)
        checkers.add(new org.plumb.dfa.checkers.taint.NumericTruncationChecker());
      // Classic injection / path taint
      checkers.add(new org.plumb.dfa.checkers.taint.SqlInjectionChecker());
      // CWE-566: tainted data reaches a DATABASE -- including as a BOUND
      // PARAMETER, where it cannot change the statement. It is a different
      // defect from CWE-89 SQL injection, and both upstream rule sets keep
      // them apart: the "Access Control/Database" rules carry CWE-566
      // and name the bound parameter, and the other upstream model library
      // has a `database_sink` primitive that is never a `sql_sink`.
      checkers.add(new org.plumb.dfa.checkers.taint.DatabaseAccessChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.CommandInjectionChecker());
      // the deserialization.sinks family has been registered CRITICAL in
      // sink-families.yaml since it was added, but no checker consumed it, so
      // CWE-502 could never be reported. The family now also uses the
      // receiver-tainted sentinel (arg_index -2) for the zero-argument
      // ObjectInputStream#readObject sinks.
      checkers.add(new org.plumb.dfa.checkers.taint.UnsafeDeserializationChecker());
      // log.sinks (CWE-117) was configured in sink-families.yaml but had
      // no consumer, so no logging call could ever be reported. Same defect
      // shape as the CWE-502 gap above. Log4Shell is delivered through exactly
      // this path: a tainted header written straight to a logger.
      checkers.add(new org.plumb.dfa.checkers.taint.LogInjectionChecker());
      // the dataflow counterpart of the AST rule
      // ast-security-null-deref-from-return-ast. Both express the rule name
      // NULL_DEREF_FROM_RETURN and both stay registered under distinct ids on
      // purpose: the AST one still fires when TAC translation fails, and this
      // one sees through containers, fields and cross-method returns. The
      // difference is not a migration.
      checkers.add(new org.plumb.dfa.checkers.taint.NullDerefFromReturnDfaChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.PathTraversalChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.LdapInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.ElInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.OgnlInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.JavaCodeInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.JcrInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.JspDynamicIncludeChecker());
      // XSS + Open Redirect + Http Response Splitting + XPath
      // Injection Format String. These checkers exist in
      // plumb-dfa/.../taint/ with matching sink families defined in
      // sink-families.yaml (xss.sinks, open-redirect.sinks,
      // header.sinks, xpath.sinks, format-string.sinks), but were
      // never wired into the engine. Adding them here is a no-op
      // when their sink family doesn't match the current file
      // (checkSinkAtCall returns cleanly), so the registration is
      // cheap. The bug is purely "missing registrations" —  the
      // engine path itself is sound after the config fix.
      checkers.add(new org.plumb.dfa.checkers.taint.XssInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.OpenRedirectChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.HttpResponseSplittingChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.XPathInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.FormatStringChecker());
      // CWE-1333 (regular expression injection). The rule watches ONE sink
      // family -- `regex.sinks`. Its shared patterns are the Java-library
      // symbols (java.util.regex.Pattern, reachable from every JVM language);
      // its per-language table holds kotlin.text.Regex and
      // scala.util.matching.Regex. One defect class, one CWE, and no language
      // sees another's sink.
      checkers.add(new org.plumb.dfa.checkers.taint.RegexInjectionChecker());
      // The defect classes the rule-pack conversion added. Each is a family the
      // converter fills from a converted rule pack, and each needs its own
      // rule -- a family with no rule is a family that can never fire.
      //   CWE-1336 server-side template injection
      //   CWE-235  HTTP parameter pollution
      //   CWE-98   dangerous file inclusion
      //   CWE-943  NoSQL injection
      //   CWE-91   JSON injection
      //   CWE-943  XQuery injection
      //   CWE-643  XSLT injection
      //   CWE-91   XML injection
      checkers.add(new org.plumb.dfa.checkers.taint.ServerSideTemplateInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.HttpParameterPollutionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.DangerousFileInclusionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.NoSqlInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.JsonInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.XQueryInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.XsltInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.XmlInjectionChecker());
      // The families that carried a pattern set with NO watcher.
      //
      // A sink family is a pattern set; a checker is what looks at it. A family
      // with patterns and no checker matches a call and the match leads nowhere:
      // the defect class could not produce a finding at all. These twelve were
      // in that state -- measured by asking which families are named by no
      // checker and no rule, then confirming the answer by searching the whole
      // tree for each family key.
      //
      //   CWE-94/95/494  code injection
      //   CWE-113        cookie injection
      //   CWE-256/798    plaintext credential storage
      //   CWE-730/776    denial of service
      //   CWE-1236       formula injection
      //   CWE-93/113     CRLF header injection
      //   CWE-297/319/326/327  insecure communication
      //   CWE-335/330    weak randomness
      //   CWE-117        log injection (the wider family)
      //   CWE-215/359/489/497  privacy violation
      //   CWE-94/95/99/502  resource injection
      //   CWE-15         setting manipulation
      checkers.add(new org.plumb.dfa.checkers.taint.CodeInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.CookieInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.CredentialExposureChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.DenialOfServiceChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.FormulaInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.HeaderInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.InsecureCommunicationChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.InsecureRandomFamilyChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.LogInjectionFamilyChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.PrivacyViolationChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.ResourceInjectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.SettingManipulationChecker());
      // The defect classes the catalogue still had no watcher for.
      //
      // A sink family with patterns and no checker matches a call and the
      // match leads nowhere: the defect class cannot produce a finding at
      // all. Nineteen families were in that state. Eight of them were folded
      // into the family that already owns their CWE -- the same API listed
      // under two family names, one of the names unwatched -- and five were
      // retired as duplicate names or as patterns that are not sinks. The
      // remaining six got a checker of their own:
      //
      //   CWE-918        server-side request forgery
      //   CWE-611        tainted XML reaches a parser
      //   CWE-261/326    tainted data chooses the algorithm or the key size
      //   CWE-311        a tainted secret reaches a store that keeps it plain
      //   CWE-942        tainted data writes the cross-origin policy
      //   CWE-927        tainted data rides an intent that names no component
      //
      // Registering a checker in checkers.yaml is NOT enough: the engine
      // takes the checker list from its caller, so a checker that is only in
      // the configuration never runs.
      checkers.add(new org.plumb.dfa.checkers.taint.SsrfChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.TaintedXmlParserChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.WeakCryptoChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.CleartextCredentialChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.CorsChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.ImplicitIntentChecker());
      // CWE-732 (unrestricted access to a file). This one is not a taint rule:
      // the dangerous call is the call itself, so it is a plain DfaChecker that
      // reads the `ownerOnly` flag out of the argument list.
      checkers.add(new org.plumb.dfa.checkers.taint.FilePermissionChecker());
      // Cleartext / DB credential / external config sinks.
      // These were also silently missing from the registration.
      // DBCredentialSinkChecker fires on
      // DriverManager.getConnection(...) with a tainted password
      // (CWE-256); CleartextNetworkCredentialSinkChecker fires on
      // network send with credential sinks; ExternalConfigSettingSink
      // fires on System.setProperty / JNDI lookups with tainted data
      // (CWE-15).
      checkers.add(new org.plumb.dfa.checkers.taint.CleartextNetworkCredentialSinkChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.CleartextTxChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.DBCredentialSinkChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.ExternalConfigSettingSinkChecker());
      // Trust / sensitive
      checkers.add(new org.plumb.dfa.checkers.taint.TrustBoundaryViolationChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.SensitivePIILeakChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.SessionFixationChecker());
      // Reflection / native / crypto
      checkers.add(new org.plumb.dfa.checkers.taint.UnsafeReflectionChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.UnsafeJniChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.BadCertVerificationChecker());
      // Unused / dead code
      checkers.add(new org.plumb.dfa.checkers.taint.UnusedAssignmentChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.UnusedVariableChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.UnusedParameterChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.UnusedFieldChecker());
      checkers.add(new org.plumb.dfa.checkers.taint.AssignmentInConditionalChecker());
      if (config.runConstantCondition)
        checkers.add(new org.plumb.dfa.checkers.taint.LiteralBooleanConditionChecker());
      // UseAfterFreeChecker (CWE-416) is deliberately absent. CWE-416 is a C/C++ memory-safety
      // weakness -- `free(p)` then use `p`. Java has no free(), the GC owns memory, and
      // `close()` followed by another call is legal Java (the JDK relies on it for
      // try-with-resources' suppressed-close path). Alias information is still worth having --
      // it just belongs to the pointer analysis infrastructure, where it serves null-deref
      // and taint, rather than to a rule about freed memory.
      checkers.add(new org.plumb.dfa.checkers.taint.UncontrolledRecursionChecker());
    }
    // The loose (path-insensitive, no-taint-required) checkers are not registered. The strict
    // `plumb.numeric.*` / `plumb.boundary.*` rules cover CWE-190/191, and `plumb.ast.*`
    // `CatchGenericException` covers class-level CWE-491/580/585.
    return filterByLanguage(filterByConfiguredLanguageScope(checkers));
  }

  /**
   * Drop the checkers whose CONFIGURED language scope does not include this run's languages.
   *
   * <p>This is the gate that makes the request literally true. The rule's own scope ({@link
   * #filterByLanguage}) says which languages a defect class is expressed in; the configuration's
   * scope says which languages the checker is MECHANISM for. A checker that hardcodes {@code
   * java.lang.Math.min} is Java-only by its own code, whatever the rule says.
   *
   * <p>The checkers are matched by CLASS, because that is the only identifier the two mechanisms
   * share: the configuration names a class, and the engine holds an instance of it.
   *
   * <p>A checker the configuration does not mention is KEPT: the configuration is a catalogue of
   * the rules a user can turn on and off, and a checker that is wired but not catalogued has
   * declared no scope to contradict.
   */
  private List<org.plumb.dfa.engine.DfaChecker> filterByConfiguredLanguageScope(
      List<org.plumb.dfa.engine.DfaChecker> checkers) {
    if (languageScopedCheckers.isEmpty()) return checkers;
    org.plumb.dfa.config.LanguageFilter filter = languageFilter();
    if (!filter.isSelective()) return checkers;
    List<org.plumb.dfa.engine.DfaChecker> kept = new java.util.ArrayList<>();
    for (org.plumb.dfa.engine.DfaChecker c : checkers) {
      org.plumb.dfa.config.CheckerConfig scope = languageScopedCheckers.get(c.getClass().getName());
      if (scope == null) {
        // The configuration does not catalogued this checker, so it has declared
        // no scope to contradict. It stays.
        kept.add(c);
        continue;
      }
      if (filter.keepsRule(scope.languages())) {
        kept.add(c);
      } else {
        LOG.info("language filter: dropped the checker {} ({})", c.name(), scope.languages());
      }
    }
    return kept;
  }

  /**
   * Drop the checkers whose rule belongs to a language this run does not analyse.
   *
   * <p>A rule declares the languages it applies to. A rule with no declaration applies to every
   * language, so it survives. A rule that declares, say, {@code kotlin} is dropped from a Java-only
   * run -- and it is dropped HERE, at registration, so it never walks an instruction.
   *
   * <p>This is the checker's own gate, {@link org.plumb.dfa.engine.DfaChecker#supports}, applied
   * one layer earlier. The runtime gate stays as the second line of defence: a checker that
   * survived registration can still decline a function of another language.
   *
   * <p><b>Two scopes, and the effective scope is their intersection.</b> The RULE says which
   * languages the defect class is expressed in; the CONFIGURATION says which languages the
   * checker's own mechanism is for. A checker whose rule names every language but whose
   * configuration names {@code java} is still Java-only, because the second scope is the one that
   * says what the checker can actually see. Both are consulted here.
   */
  private List<org.plumb.dfa.engine.DfaChecker> filterByLanguage(
      List<org.plumb.dfa.engine.DfaChecker> checkers) {
    org.plumb.dfa.config.LanguageFilter filter = languageFilter();
    if (!filter.isSelective()) return checkers;
    List<org.plumb.dfa.engine.DfaChecker> kept = new java.util.ArrayList<>();
    for (org.plumb.dfa.engine.DfaChecker c : checkers) {
      if (!(c instanceof org.plumb.dfa.taint.TaintedCheckerBase tcb)) {
        // A checker that does not expose a rule has no language restriction to honour.
        kept.add(c);
        continue;
      }
      java.util.Set<String> declared = tcb.rule() == null ? null : tcb.rule().languages();
      if (filter.keepsRule(declared)) {
        kept.add(c);
      } else {
        LOG.info("language filter: dropped the checker {} ({})", c.name(), declared);
      }
    }
    return kept;
  }

  /**
   * The directory to index for cross-file {@code public static final} resolution.
   *
   * <p>The parent directory of the first scanned source file. Deliberately NOT an upward walk: the
   *
   * <p>old rule walked up from the sample file, so a scan rooted in a temporary directory indexed
   * the
   *
   * <p>whole temporary tree -- tens of thousands of files -- on every analysis.
   *
   * <p>{@code null} when the scan has no source path at all, which disables cross-file resolution.
   *
   * <p>The engine treats an absent resolver as "no cross-file static-field information", never as
   * "no
   *
   * <p>static fields".
   */
  private static java.nio.file.Path resolverRootFor(
      java.util.Map<String, java.nio.file.Path> functionSourcePaths) {
    if (functionSourcePaths == null) return null;
    for (java.nio.file.Path p : functionSourcePaths.values()) {
      if (p == null) continue;
      java.nio.file.Path parent = p.toAbsolutePath().getParent();
      if (parent != null) return parent;
    }
    return null;
  }

  /**
   * The language filter for this run.
   *
   * <p>Derived from {@link ScanConfig#languages}. Empty means "every language", which is the
   * default: the filter only does something when the user asked for a subset.
   */
  org.plumb.dfa.config.LanguageFilter languageFilter() {
    return org.plumb.dfa.config.LanguageFilter.of(config.languages);
  }

  /**
   * Build the engine for the unified (DFA) pass.
   *
   * <p>The worker count comes from the plan, so {@code --max-parallelism} reaches the analysis and
   * not only the parse phase.
   *
   * <p>When the run is parallel, the engine is given a CHECKER FACTORY instead of a single shared
   * list. A checker carries per-function state -- the tainted variables, the taint paths, the field
   * tracker, the trace builder -- and {@code initFunction} clears it at every function boundary.
   * Two workers sharing one checker instance would have one worker's clear erase the other's facts
   * mid-walk, and the run would lose findings rather than gain them. The factory gives every worker
   * its own, equivalent, instances.
   */
  private org.plumb.dfa.engine.DfaEngine newUnifiedEngine(
      org.plumb.dfa.config.EngineConfig cfg,
      List<org.plumb.dfa.engine.DfaChecker> checkers,
      String callGraphStrategy) {
    // The heap guard decides the worker count NOW, not at the start of the scan: the
    // project has been parsed by this point, so the heap pressure is real and measured.
    int workers = Math.max(0, heapGuard.effectiveWorkers());
    java.util.function.Supplier<List<org.plumb.dfa.engine.DfaChecker>> factory =
        workers > 0 ? this::buildDfaCheckers : null;
    org.plumb.dfa.engine.DfaEngine engine =
        new org.plumb.dfa.engine.DfaEngine(
            cfg, checkers, callGraphStrategy, null, workers, factory);
    // The module scope. The other modules are still READ -- they are on the classpath, and a
    // callee that lives in a dependency module has to resolve, or the call graph breaks at the
    // module boundary and the taint never crosses it. What the scope changes is where a FINDING
    // may be reported.
    if (config.module != null && !config.module.isBlank()) {
      engine.withModuleScope(config.module);
      LOG.info("module scope: {}", config.module);
    }
    return engine;
  }

  private List<Finding> runUnifiedCheckers(
      org.plumb.tac.ir.MergedProgram merged,
      org.plumb.tac.ir.TacProgram programView,
      java.util.Map<String, java.nio.file.Path> functionSourcePaths,
      List<org.plumb.dfa.engine.DfaChecker> checkers) {
    if (checkers.isEmpty()) return List.of();
    try {
      // Load the base EngineConfig from classpath so the
      // sql.sinks / command.sinks / xss.sinks / file-path.sinks
      // / ... sink families defined in sink-families.yaml are
      // registered. Without this, every taint-family checker
      // (SqlInjection / CommandInjection / XSS / PathTraversal /
      // LDAP / EL / OGNL / CodeInjection / JCR / JspDynamicInclude /
      // ...) silently no-ops: their checkSinkAtCall() returns
      // immediately when the registry is empty. The older minimal config
      // used SinkFamilyRegistry.empty() + CheckerRegistry.empty() for
      // the 7 baseline DFA checkers (NullDeref, IntOverflow, ...),
      // which don't need sink families. But the same call also
      // runs the taint-family checkers listed in buildDfaCheckers()
      // and those DO need the sink families, so we load the
      // classpath defaults and keep the conservative TuningConfig.
      org.plumb.dfa.config.EngineConfig baseCfg =
          org.plumb.dfa.config.ConfigLoader.loadFromClasspath();
      // The language filter is applied to the LOADED configuration: the sink families of the
      // languages that were not selected, and their per-language patterns, are never
      // registered. A Java-only scan therefore does not compile the Kotlin and the Scala
      // patterns at all.
      org.plumb.dfa.config.LanguageFilter languageFilter = languageFilter();
      if (languageFilter.isSelective()) {
        baseCfg = baseCfg.withSinkFamilies(languageFilter.filterFamilies(baseCfg.sinkFamilies()));
        // The checker CONFIGURATION is filtered on the same terms, so a
        // checker that declares the languages it applies to is not even
        // registered when none of them was selected.
        baseCfg = baseCfg.withCheckers(languageFilter.filterCheckers(baseCfg.checkers()));
        LOG.info(
            "language filter: {} (sink families and checkers narrowed before registration)",
            languageFilter.describe());
      }
      org.plumb.dfa.config.EngineConfig unifiedCfg =
          new org.plumb.dfa.config.EngineConfig(
              baseCfg.sinkFamilies(),
              org.plumb.dfa.fact.BuiltinFactKinds.registryWithBuiltins(),
              baseCfg.checkers(),
              baseCfg.cweMappings(),
              new org.plumb.dfa.config.TuningConfig(
                  2,
                  1000,
                  1000,
                  100,
                  4,
                  false,
                  false,
                  false,
                  "full",
                  "cwe_family",
                  "sarif",
                  false,
                  0),
              // forward the
              // receiver-passthrough
              // patterns loaded from
              // receiver-passthrough.yaml
              // so TaintedCheckerBase's
              // receiver->first fallback is
              // config-gated. The legacy
              // 5-arg constructor would
              // silently drop the list and
              // disable the fallback
              // entirely.
              baseCfg.receiverPassthroughTable(),
              // the argument->result table,
              // which is the shape the
              // receiver table cannot
              // express: a method that builds
              // its result from an ARGUMENT.
              baseCfg.languageSemantics(),
              baseCfg.argumentPassthrough());
      // Pass per-enclosing-class
      // source paths so the unified
      // FindingReporter can fill in
      // filePath on findings whose
      // checker left it empty.
      // Decide the engine entry point based on cgStrategy.
      // "global" (default) routes to the cross-TU path which
      // uses DfaEngine.runWithGlobalStore. All other names
      // (cha / rta / vta / 1cfa / stub) route to the
      // TU-local DfaEngine.run(programView) path.
      org.plumb.dfa.engine.FindingReporter reporter;
      // The engine is kept so its budget report can be surfaced after the run. An incomplete
      // analysis produces the same findings as a complete one, so without this the user has no
      // way to know that some functions were only partly analysed.
      org.plumb.dfa.engine.DfaEngine unifiedEngine = null;
      // Cross-file `public static final` resolution needs to READ source, so
      // it is asked for explicitly and only here, on the source-present path.
      // The root is the input directory of the scan -- NOT an upward walk:
      // an upward walk from a file in a temporary directory lands on the
      // whole temporary tree, which is tens of thousands of files read and
      // regex-scanned per analysis.
      java.nio.file.Path resolverRoot = resolverRootFor(functionSourcePaths);
      if (merged != null && "global".equalsIgnoreCase(config.cgStrategy)) {
        org.plumb.dfa.engine.DfaEngine unified =
            newUnifiedEngine(unifiedCfg, checkers, "global")
                .withFunctionSourcePaths(functionSourcePaths)
                .withStaticFieldResolverRoot(resolverRoot);
        unifiedEngine = unified;
        reporter = unified.runWithGlobalStore(merged.sources(), heapGuard.effectiveWorkers());
      } else if ("global".equalsIgnoreCase(config.cgStrategy) && programView != null) {
        // Single-file path with cgStrategy=global: build a
        // GlobalTACStore around the one program so the
        // cross-TU engine code runs. The store sees one TU
        // and the global call graph collapses to the
        // program's internal edges, which is identical to
        // running the CHA path on the same program.
        org.plumb.dfa.engine.DfaEngine unified =
            newUnifiedEngine(unifiedCfg, checkers, "global")
                .withFunctionSourcePaths(functionSourcePaths)
                .withStaticFieldResolverRoot(resolverRoot);
        unifiedEngine = unified;
        reporter =
            unified.runWithGlobalStore(
                java.util.List.of(programView), heapGuard.effectiveWorkers());
      } else {
        String effectiveCg = config.cgStrategy;
        if (effectiveCg == null || effectiveCg.isEmpty()) {
          effectiveCg = "cha";
        }
        org.plumb.dfa.engine.DfaEngine unified =
            newUnifiedEngine(unifiedCfg, checkers, effectiveCg)
                .withFunctionSourcePaths(functionSourcePaths)
                .withStaticFieldResolverRoot(resolverRoot);
        unifiedEngine = unified;
        reporter = unified.run(programView);
      }
      if (unifiedEngine != null) {
        org.plumb.dfa.engine.AnalysisBudget.BudgetReport budgetReport =
            unifiedEngine.budgetReport();
        this.budgetReport = budgetReport;
        this.moduleScopeReport = unifiedEngine.moduleScope();
        if (budgetReport != null && !budgetReport.complete()) {
          // WARN, not INFO: an incomplete analysis is a caveat on the result, and the
          // caveat belongs where the user will see it.
          LOG.warn("Analysis was incomplete: {}", budgetReport.summary());
        }
        // The module boundary. A single-module run has to say what it did NOT look at:
        // "no findings in this module" and "the findings were in the other modules" are
        // different statements, and only the second one is a caveat on the result.
        org.plumb.dfa.engine.ModuleScope scope = unifiedEngine.moduleScope();
        if (scope.isLimited()) {
          LOG.info("module scope: {}", scope.summary());
          if (scope.crossingCallers() > 0) {
            LOG.info(
                "module scope: {} caller(s) called out of module {}; the taint still crossed the"
                    + " boundary, and the crossings are recorded",
                scope.crossingCallers(),
                scope.module());
          }
          if (scope.outOfScopeFindings() > 0) {
            LOG.info(
                "module scope: {} finding(s) were outside module {} and are not reported",
                scope.outOfScopeFindings(),
                scope.module());
          }
        }
      }
      List<Finding> legacy = new ArrayList<>();
      for (org.plumb.reporter.Finding uf : reporter.all()) {
        // R-PATCH v2: filter findings by rule id against the
        // configured disabled/whitelist lists. The registry-based
        // patch in buildRegistry() does NOT cover DFA checkers
        // whose rule id is not in the RuleRegistry (e.g.
        // "UncheckedReturn", "integer-overflow"). Filtering here
        // catches every finding the engine produces.
        if (!isRuleIdAllowed(uf.ruleId())) continue;
        legacy.add(convertUnifiedFinding(uf));
      }
      return legacy;
    } catch (Exception e) {
      LOG.warn("plumb-dfa failure: {}", e.toString());
      return List.of();
    }
  }

  /**
   * Whether an AST checker is allowed to run under the current filter.
   *
   * <p>A checker has several identities, and the filter has to accept any of them, because each one
   *
   * <p>is a name a user can reasonably type:
   *
   * <ul>
   *   <li>the checker's {@code name()} -- a kebab-case slug ({@code unchecked-return});
   *   <li>the checker's class simple name -- {@code UncheckedReturnCheck};
   *   <li>the RULE ID the findings carry -- {@code ast-security-unchecked-return};
   *   <li>the rule's name -- {@code UNCHECKED_RETURN}.
   * </ul>
   *
   * <p><b>Why the rule id matters most.</b> It is the identity the report prints. A user who reads
   *
   * <p>{@code ast-bug-incorrect-return-comparison} off a finding and passes it back to {@code
   * --rule}
   *
   * <p>must get that rule back. When the AST registration gate only consulted the checker's name
   * and
   *
   * <p>class name, that round trip selected nothing, and the scan came back empty -- which reads as
   *
   * <p>"the rule found nothing" rather than "the filter matched nothing".
   *
   * <p>The class name is matched with and without its {@code Check} suffix, because the per-CWE
   *
   * <p>configs were written against the shorter form ({@code UncheckedReturn}) and those entries
   * are
   *
   * <p>what the CWE coverage numbers are measured against.
   */
  private boolean isCheckerAllowed(org.plumb.style.AstChecker c) {
    if (isRuleIdAllowed(c.name())) {
      return true;
    }
    String className = c.getClass().getSimpleName();
    if (isRuleIdAllowed(className)) {
      return true;
    }
    if (className.endsWith("Check")
        && isRuleIdAllowed(className.substring(0, className.length() - "Check".length()))) {
      return true;
    }
    RuleDef def = c.rule();
    if (def != null && def.ruleId() != null) {
      if (isRuleIdAllowed(def.ruleId().id())) {
        return true;
      }
      if (isRuleIdAllowed(def.ruleId().name())) {
        return true;
      }
      // The rule's DECLARED aliases. A rule declares the equivalent ids it
      // has in other rule sources (`dfa-security-unchecked-return`,
      // `unchecked-return`), and a per-CWE config may well name the rule by
      // one of those instead of by the canonical id. Without this the
      // config's entry selects nothing.
      //
      // An alias is matched EXACTLY. Peeling an alias turns it into a shape,
      // and `dfa-style-catch-generic-exception` peels to
      // `catch-generic-exception` -- which is another rule's name, so the
      // alias would select that other rule.
      for (String alias : def.aliases()) {
        if (filterMatches(alias, config.enabledRuleNames, true)
            || filterMatches(alias, config.disabledRuleNames, true)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean isRuleIdAllowed(String ruleId) {
    return isRuleIdAllowed(ruleId, currentRuleRegistry.get());
  }

  /**
   * overload that accepts a {@link RuleRegistry} so callers can pass the per-scan registry built by
   *
   * <p>{@link #buildRegistry()}. The disabled-by-default gate looks up the rule id in the registry
   * and
   *
   * <p>drops it when the registry says {@code enabled=false} AND the user did not override via yaml
   *
   * <p>rules.enabled or --rule. Production paths always pass the live registry; smoke tests may
   * pass
   *
   * <p>{@code null} to keep the legacy behaviour.
   */
  private boolean isRuleIdAllowed(String ruleId, RuleRegistry liveRegistry) {
    if (ruleId == null) return true;
    // a rule whose DEFAULT_RULE declared
    // enabled=false (Juliet-driven policy decision)
    // must be skipped unless the user explicitly
    // flipped it on via yaml rules.enabled /
    // --rule / cliRuleFilter. We do the disabled-
    // by-default gate FIRST so a rule whose default
    // is `enabled=false` is treated like a
    // blacklist hit, then the explicit user-driven
    // overrides below re-enable it.
    boolean defaultDisabled = false;
    if (liveRegistry != null) {
      RuleDef def = liveRegistry.byName(ruleId).orElse(null);
      if (def != null && !def.enabled()) {
        defaultDisabled = true;
      }
    }
    // audit: wire `--rule <id>` CLI flag through.
    // SastMain populates `config.cliRuleFilter` from every `--rule`
    // arg; when the list is non-empty ONLY those rules fire. The
    // filter accepts both the bare form ("dfa.foo") and the
    // canonical "plumb.<domain>.<name>" form ( dual-form match).
    //
    // also strip the `pl-<NNNN>-` prefix
    // that the new style rules use (e.g.
    // `pl-0763-incorrect-return-comparison` matches the
    // user-yaml short form `incorrect-return-comparison`).
    if (!config.cliRuleFilter.isEmpty()) {
      // The SAME predicate the whitelist uses, so an id printed in the
      // report round-trips: `--rule <printed-id>` selects the rule that
      // produced it. The comparison is symmetric — both the argument and
      // the rule id are expanded into every form they can be written in.
      return filterMatches(ruleId, config.cliRuleFilter);
    }
    // Blacklist always wins (when explicitly listed as disabled)
    if (filterMatches(ruleId, config.disabledRuleNames)) return false;
    // Whitelist mode: only emit rule ids that are explicitly enabled.
    // accept both the bare rule id (e.g.
    // "dfa.reachable-assertion") and the legacy
    // "plumb." prefixed form that some DFA
    // checkers emit (e.g.
    // "plumb.dfa.reachable-assertion"). The yaml
    // is written with the bare form (matching
    // RuleRegistry plumb_rule_to_cwe.csv) but
    // the engine's Finding.ruleId() carries the
    // "plumb." prefix — without this dual-form
    // match every DFA-only rule would be silently
    // dropped by the whitelist filter.
    if (config.whitelistMode && !config.enabledRuleNames.isEmpty()) {
      return filterMatches(ruleId, config.enabledRuleNames);
    }
    return !defaultDisabled;
  }

  /**
   * Whether a style finding survives the filter.
   *
   * <p>A finding carries the rule's id, the rule's name, the checker's class name, and the rule's
   *
   * <p>declared aliases. A filter entry may name any of them, and the class name is accepted with
   * and
   *
   * <p>without its {@code Check} suffix because the per-CWE configs were written against the
   * shorter
   *
   * <p>form.
   *
   * <p>Both post-style filters call this, so the merged path and the single-file path can never
   *
   * <p>disagree about which findings a whitelist admits.
   *
   * @param f the finding
   * @param entries the filter entries
   * @return true when an entry names the finding's rule
   */
  private boolean findingIsAllowed(Finding f, java.util.Collection<String> entries) {
    if (f == null || f.rule() == null) {
      return true;
    }
    String id = f.rule().ruleId().id();
    String name = f.rule().ruleId().name();
    String checkerClass = f.rule().checkerClass();
    String clsSimple =
        checkerClass == null ? null : checkerClass.substring(checkerClass.lastIndexOf('.') + 1);
    if (filterMatches(id, entries)
        || filterMatches(name, entries)
        || (clsSimple != null && filterMatches(clsSimple, entries))
        || (clsSimple != null
            && clsSimple.endsWith("Check")
            && filterMatches(
                clsSimple.substring(0, clsSimple.length() - "Check".length()), entries))) {
      return true;
    }
    // An alias is matched EXACTLY -- see the note in isCheckerAllowed.
    for (String alias : f.rule().aliases()) {
      if (filterMatches(alias, entries, true)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code ruleId} is named by any entry in {@code entries}.
   *
   * <p><b>This is the ONE place where a filter entry is compared with a rule id.</b> It exists
   *
   * <p>because the comparison used to be re-implemented in four places (the AST registration gate,
   * the
   *
   * <p>post-style finding filter, the registry builder, and the CLI {@code --rule} filter), each
   * with
   *
   * <p>its own idea of what a "bare id" is. They disagreed:
   *
   * <ul>
   *   <li>the registry builder stripped at the FIRST dot, so {@code
   *       ast-bug-incorrect-return-comparison} became {@code bug-incorrect-return-comparison};
   *   <li>the post-style filter stripped at the first dot as well, so the same id became {@code
   *       bug-incorrect-return-comparison};
   *   <li>the CLI filter stripped only the FIRST domain token, so {@code
   *       ast-bug-incorrect-return-comparison} became {@code bug-incorrect-return-comparison} —
   *       <p>matching nothing.
   * </ul>
   *
   * <p>The consequence was that a rule id printed in the report could not be passed back to {@code
   * --rule} to select the rule that produced it, and a per-CWE YAML whitelist written against the
   *
   * <p>printed id selected nothing. The scan came back empty, which reads as "the rule found
   * nothing"
   *
   * <p>rather than "the filter matched nothing".
   *
   * <p>Now every comparison goes through {@link #ruleIdForms(String)}, which drops leading domain
   *
   * <p>tokens while keeping EVERY suffix. Both sides of the comparison are expanded, so the match
   * is
   *
   * <p>symmetric: an entry may be written in the canonical form or in any shorter form, and the
   * rule
   *
   * <p>id may be either.
   *
   * @param ruleId the rule id the finding or checker carries
   * @param entries the filter entries (yaml whitelist, {@code --rule} args, …)
   * @return true when an entry names this rule
   */
  private boolean filterMatches(String ruleId, java.util.Collection<String> entries) {
    return filterMatches(ruleId, entries, false);
  }

  /**
   * Whether {@code ruleId} is named by any entry in {@code entries}.
   *
   * @param ruleId the rule id the finding or checker carries
   * @param entries the filter entries (yaml whitelist, {@code --rule} args, ...)
   * @param exact when true, only the literal id and its legacy-map resolution are compared. A
   *     <p>DECLARED ALIAS is matched this way, because an alias is a name, not a shape: peeling it
   *     <p>would make {@code dfa-style-catch-generic-exception} reduce to {@code
   *     catch-generic-exception}, which is another rule's name, and the alias would then select
   *     <p>that other rule.
   * @return true when an entry names this rule
   */
  private boolean filterMatches(
      String ruleId, java.util.Collection<String> entries, boolean exact) {
    if (ruleId == null || entries == null || entries.isEmpty()) {
      return false;
    }
    // A retired rule id and the id it was renamed to are the SAME rule, so
    // both sides of the comparison are resolved through the legacy map
    // first.  Without this, a config written against the old id selects
    // nothing and the CWE's recall silently reads zero.
    String canonicalRuleId = LegacyRuleAliases.canonical(ruleId);
    java.util.List<String> ruleForms;
    if (exact) {
      ruleForms = java.util.List.of(canonicalRuleId, ruleId);
    } else {
      ruleForms = ruleIdForms(canonicalRuleId);
    }
    for (String entry : entries) {
      if (entry == null) continue;
      if (ruleForms.contains(entry)) return true;
      // The entry is normalised too, so an entry written in the canonical
      // form (`plumb.style.random-iv-cbc`) still names a rule whose own id
      // is the bare slug (`random-iv-cbc`).
      String canonicalEntry = LegacyRuleAliases.canonical(entry);
      if (exact) {
        if (ruleForms.contains(canonicalEntry)) return true;
        continue;
      }
      for (String entryForm : ruleIdForms(canonicalEntry)) {
        if (!ruleForms.contains(entryForm)) continue;
        // The entry form may be a PEELED form -- the id with its engine
        // and domain tokens removed. A peeled form is only used when it
        // is UNAMBIGUOUS: when exactly one live rule has it.
        //
        // Twelve pairs of live rules share a peeled form, and eight of
        // those pairs carry DIFFERENT CWEs. `null-deref-from-return` is
        // both the AST rule's form (CWE-690) and the DFA rule's form
        // (CWE-476), so `--rule dfa-taint-null-deref-from-return`
        // selected both -- and a TN case for CWE-476 was scored as a
        // false positive because the CWE-690 rule fired on the same
        // line. An ambiguous bare slug now selects nothing, and the
        // user names the rule they mean.
        if (isAmbiguousForm(entryForm, ruleId)) continue;
        return true;
      }
      // The `ast-style-<bare>-style` emit shape: the era whitelist carries
      // the bare slug with that prefix and suffix, while the checker's
      // name() returns the bare slug.
      String astStyleBare = canonicalEntry;
      if (astStyleBare.startsWith("ast-style-")) {
        astStyleBare = astStyleBare.substring("ast-style-".length());
      }
      if (astStyleBare.endsWith("-style")) {
        astStyleBare = astStyleBare.substring(0, astStyleBare.length() - "-style".length());
      }
      if (ruleForms.contains(astStyleBare) && !isAmbiguousForm(astStyleBare, ruleId)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Is this bare form shared by more than one live rule?
   *
   * <p>A "form" is the rule id with its leading engine and domain tokens peeled off -- the short
   *
   * <p>spelling a user may reasonably type. Twelve pairs of live rules share one, and eight of
   * those
   *
   * <p>pairs carry DIFFERENT CWEs, so a filter that used an ambiguous form would select a rule the
   *
   * <p>user did not name.
   *
   * <p>The live rule set is read from the ENGINE, so the answer cannot drift from the rules that
   *
   * <p>actually ship. A rule id that is not live is not ambiguous: only one live rule can have its
   *
   * <p>form.
   *
   * @param form the peeled form
   * @param theRuleId the rule the form is being tested against
   * @return true when more than one live rule has this form
   */
  private boolean isAmbiguousForm(String form, String theRuleId) {
    if (form == null || form.isEmpty()) return false;
    java.util.Collection<String> live = liveRuleIds();
    if (live == null || live.isEmpty()) {
      // No live set: the answer would be a guess, and a guess that
      // silently disables a filter is worse than the collision it
      // prevents. Assume the form is unique.
      return false;
    }
    // A form is owned by a rule when it is one of that rule's identities:
    // its emitted id, its legacy registry name, or its checker class
    // name, or when it is a PEELED form of one of them.
    //
    // The comparison is case-insensitive, because the peeled form of a
    // legacy name (`NULL_DEREF_FROM_RETURN` -> `null-deref-from-return`)
    // is lower-case while the name itself is upper-case.
    //
    // The rule's own id is NOT exempt. A rule whose identity happens to
    // BE the bare slug -- and several do: an AST checker's `name()` is
    // its bare slug -- is exactly the case the guard exists for. The
    // concrete defect was: the AST checker's name is
    // `null-deref-from-return`, so `--rule dfa-taint-null-deref-from-return`
    // matched it through the ENTRY's peeled form, and the CWE-690 rule
    // came through a filter that named the CWE-476 rule.
    String wanted = form.toLowerCase(java.util.Locale.ROOT);
    int owners = 0;
    for (String identity : live) {
      if (identity == null || identity.isEmpty()) continue;
      if (identity.toLowerCase(java.util.Locale.ROOT).equals(wanted)) {
        owners++;
      } else {
        // A PEELED form of an identity owns the form too. This is the case the guard
        // exists for: the AST checker's `name()` is the bare slug
        // `null-deref-from-return`, so the form is owned by the AST rule (CWE-690)
        // AND, once peeled, by the DFA rule (CWE-476). Without counting the peeled
        // forms, `--rule dfa-taint-null-deref-from-return` selects both, and a TN
        // case for CWE-476 is scored as a false positive because the CWE-690 rule
        // fired on the same line.
        for (String f : ruleIdForms(identity)) {
          if (f.toLowerCase(java.util.Locale.ROOT).equals(wanted)) {
            owners++;
            break;
          }
        }
      }
      if (owners > 1) return true;
    }
    return false;
  }

  /**
   * The ids of every rule the engine can emit.
   *
   * <p>Read from the live registries, so the answer is the engine's own. Cached, because the filter
   *
   * <p>asks the question once per rule per filter entry and the answer does not change during a
   * scan.
   */
  private java.util.Collection<String> liveRuleIds() {
    java.util.Collection<String> cached = liveRuleIdsCache;
    if (cached != null) return cached;
    java.util.Set<String> ids = new java.util.LinkedHashSet<>();
    try {
      for (org.plumb.style.AstChecker checker : buildStyleChecks()) {
        for (org.plumb.rules.RuleDef def : checker.allRules()) {
          if (def == null || def.ruleId() == null) continue;
          addRuleIdentities(ids, def.ruleId().id(), def.ruleId().name(), def.checkerClass());
        }
      }
      for (org.plumb.dfa.engine.DfaChecker checker : buildDfaCheckers()) {
        try {
          if (checker instanceof org.plumb.dfa.taint.TaintedCheckerBase) {
            org.plumb.dfa.taint.TaintRule rule =
                ((org.plumb.dfa.taint.TaintedCheckerBase) checker).rule();
            if (rule == null) continue;
            // The taint rule's id IS the emitted id; the checker name is
            // the class name the filter also accepts.
            addRuleIdentities(ids, rule.ruleId(), checker.name(), checker.getClass().getName());
            continue;
          }
          org.plumb.rules.RuleDef def = checker.ruleDef();
          if (def == null || def.ruleId() == null) continue;
          addRuleIdentities(ids, def.ruleId().id(), def.ruleId().name(), def.checkerClass());
        } catch (RuntimeException ignored) {
          // A checker that cannot be read simply does not contribute.
        }
      }
    } catch (RuntimeException ignored) {
      // If the live set cannot be built, the ambiguity check degrades to
      // "assume unique" rather than to "disable every filter".
    }
    liveRuleIdsCache = ids;
    return ids;
  }

  /**
   * Add every identity a rule answers to.
   *
   * <p>The filter compares an entry against a rule's ID, against the rule's LEGACY NAME, and
   *
   * <p>against the checker class simple name (with and without the "Check" suffix). Each of those
   * has
   *
   * <p>its own peeled forms, and a collision between any two of them lets one filter entry select
   * two
   *
   * <p>rules.
   *
   * <p>That is not hypothetical: the collision that let the AST CWE-690 rule through {@code
   * --ruledfa-taint-null-deref-from-return} was between the ENTRY's peeled form and the AST rule's
   * legacy
   *
   * <p>name's peeled form -- not between the two rules' ids. So the live set has to carry the
   * legacy
   *
   * <p>names too.
   *
   * @param ids the set to add to
   * @param id the rule's emitted id
   * @param name the rule's legacy registry name
   * @param checkerClass the rule's checker class
   */
  private static void addRuleIdentities(
      java.util.Set<String> ids, String id, String name, String checkerClass) {
    if (id != null && !id.isBlank()) ids.add(id);
    if (name != null && !name.isBlank()) ids.add(name);
    if (checkerClass != null && !checkerClass.isBlank()) {
      String simple = checkerClass.substring(checkerClass.lastIndexOf('.') + 1);
      ids.add(simple);
      if (simple.endsWith("Check")) {
        ids.add(simple.substring(0, simple.length() - "Check".length()));
      }
      if (simple.endsWith("Checker")) {
        ids.add(simple.substring(0, simple.length() - "Checker".length()));
      }
    }
  }

  /**
   * Every form a rule id can reasonably be written in.
   *
   * <p>A rule id is built from an ENGINE token, a DOMAIN token and a NAME: {@code
   * plumb.style.cwe600-uncaught-exception}. Different callers spell the same rule differently, and
   *
   * <p>each spelling is something a user can reasonably type:
   *
   * <ul>
   *   <li>the full id, exactly as the report prints it;
   *   <li>without the {@code plumb.} prefix;
   *   <li>without the ENGINE and DOMAIN tokens -- {@code catch-generic-exception} for {@code
   *       ast-error-catch-generic-exception};
   *   <li>without the {@code pl-<NNNN>-} numeric prefix the newer style rules use.
   * </ul>
   *
   * <p><b>Why the domain token needs care.</b> {@code ast-error-catch-generic-exception} has TWO
   *
   * <p>domain-ish tokens ({@code ast} and {@code error}) before the name, while {@code
   * plumb.style.cwe600-uncaught-exception} has one ({@code style}) after a dotted prefix. Stripping
   *
   * <p>only the first token left {@code error-catch-generic-exception}, which matches nothing -- so
   *
   * <p>the id printed in the report could not be passed back to {@code --rule} to select the rule
   * that
   *
   * <p>produced it.
   *
   * <p><b>Why the peeling has to stop at the NAME.</b> Peeling every token is too much. {@code
   * ast-error-catch-generic-exception} peels down to {@code generic-exception}, which is ALSO the
   *
   * <p>tail of {@code ast-error-generic-exception} and {@code ast-error-throw-generic-exception} --
   *
   * <p>three unrelated rules. A filter that named one of them would select all three.
   *
   * <p>So the peeling is driven by a VOCABULARY of the tokens that are actually engines or domains,
   *
   * <p>and it stops as soon as the remainder is not one of them. The vocabulary is data, not a
   * guess:
   *
   * <p>it is the set of engine and domain tokens the rule ids are built from.
   *
   * @param ruleId the rule id
   * @return the forms to match a filter entry against
   */
  private static java.util.List<String> ruleIdForms(String ruleId) {
    java.util.List<String> forms = new java.util.ArrayList<>(6);
    if (ruleId == null || ruleId.isEmpty()) {
      return forms;
    }
    forms.add(ruleId);
    // Drop a dotted `plumb.` prefix.
    String s = ruleId.startsWith("plumb.") ? ruleId.substring("plumb.".length()) : ruleId;
    if (!s.equals(ruleId)) {
      forms.add(s);
    }
    // Drop a leading `pl-<NNNN>-` numeric prefix.
    if (s.startsWith("pl-") && s.length() > 4 && Character.isDigit(s.charAt(3))) {
      int dash = s.indexOf('-', 3);
      if (dash > 3) {
        String stripped = s.substring(dash + 1);
        forms.add(stripped);
        s = stripped;
      }
    }
    // Drop leading ENGINE / DOMAIN tokens, keeping every suffix. `ast-error-x` yields
    // `ast-error-x`, `error-x` and `x`; `style.cwe600-x` yields `style.cwe600-x`
    // and `cwe600-x`.
    //
    // The peeling stops at the NAME: only a token that is a known engine or
    // domain is dropped, so `ast-error-catch-generic-exception` peels to
    // `catch-generic-exception` and NOT to `generic-exception`. Peeling
    // further would make three unrelated rules share a form, and a filter
    // naming one of them would select all three.
    String rest = s;
    for (int i = 0; i < 4; i++) {
      int dot = rest.indexOf('.');
      int dash = rest.indexOf('-');
      int cut = -1;
      if (dot >= 0 && (dash < 0 || dot < dash)) {
        cut = dot;
      } else if (dash >= 0) {
        cut = dash;
      }
      if (cut <= 0) {
        break;
      }
      String head = rest.substring(0, cut);
      if (!isEngineOrDomainToken(head)) {
        // The head is the rule's NAME, not a domain. Stop here: everything
        // from here on is the name, and a suffix of the name belongs to a
        // different rule.
        break;
      }
      rest = rest.substring(cut + 1);
      if (!forms.contains(rest)) {
        forms.add(rest);
      }
    }
    return forms;
  }

  /**
   * The tokens that make up a rule id's ENGINE and DOMAIN part.
   *
   * <p>These are the tokens the engine actually uses to build ids: the engine that produced the
   *
   * <p>rule ({@code ast}, {@code dfa}, {@code bu}, {@code plumb}, {@code orm}) and the topic of the
   *
   * <p>rule ({@code style}, {@code error}, {@code bug}, {@code taint}, {@code security}, {@code
   * naming}, ...).
   *
   * <p>The list is deliberately a fixed vocabulary rather than "drop any leading token". Dropping
   *
   * <p>any leading token makes a rule's NAME reachable as a form, and a name like {@code
   * generic-exception} is shared by three unrelated rules -- so a filter naming one of them would
   *
   * <p>select all three.
   */
  private static final java.util.Set<String> ENGINE_AND_DOMAIN_TOKENS =
      java.util.Set.of(
          // engines
          "ast",
          "dfa",
          "bu",
          "plumb",
          "orm",
          "servlet",
          "singleton",
          // domains
          "style",
          "error",
          "bug",
          "taint",
          "security",
          "naming",
          "performance",
          "quality",
          "design",
          "concurrency",
          "numeric",
          "boundary",
          "pointer",
          "resource",
          "info",
          "ai",
          "general");

  private static boolean isEngineOrDomainToken(String token) {
    return ENGINE_AND_DOMAIN_TOKENS.contains(token);
  }

  /**
   * The engine and domain tokens the rule id peeling knows about.
   *
   * <p>Exposed to the tests, because the test that measures the collisions has to compute the
   *
   * <p>spellings the SAME way the filter computes them. A test that reimplemented the vocabulary
   * would
   *
   * <p>measure a different set of spellings than the filter uses, and the two would agree on
   * nothing.
   */
  static java.util.Set<String> engineAndDomainTokens() {
    return ENGINE_AND_DOMAIN_TOKENS;
  }

  /**
   * /** Convert a unified engine {@link org.plumb.dfa.engine.Finding} (string ruleId + cwe + file
   *
   * <p>path) into a legacy {@link Finding} (RuleDef-backed). The {@code name} field, which is what
   * the
   *
   * <p>reporter uses to identify the rule.
   */
  /**
   * The CWE ids of a finding, as a list.
   *
   * <p>A defect class can carry more than one CWE id -- the same path-traversal defect is CWE-22,
   *
   * <p>CWE-23 and CWE-36, and a corpus may number it differently from the engine. The DFA finding
   *
   * <p>holds the ids as one comma-separated string, because that is what the taint rule declares;
   * the
   *
   * <p>rule definition holds them as a list, because that is what the rule table and the report
   * read.
   */
  private static List<String> cweList(String cwe) {
    if (cwe == null || cwe.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    for (String part : cwe.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty() && !out.contains(trimmed)) {
        out.add(trimmed);
      }
    }
    return out;
  }

  private Finding convertUnifiedFinding(org.plumb.reporter.Finding uf) {
    org.plumb.rules.RuleId rid = new org.plumb.rules.RuleId(uf.ruleId(), uf.checkerName());
    RuleDef def =
        new RuleDef(
            rid,
            uf.message(),
            convertRuleDefSeverity(uf.severity()),
            RuleDef.Type.CORRECTNESS,
            cweList(uf.cwe()),
            "org.plumb.dfa.checkers." + uf.checkerName(),
            true);
    java.nio.file.Path file =
        uf.filePath() == null || uf.filePath().isEmpty()
            ? java.nio.file.Path.of("unknown")
            : java.nio.file.Path.of(uf.filePath());
    // pass the structured FlowStep list through to
    // the reporter as-is. Both the DFA engine and the
    // reporter now use the unified
    // {@link org.plumb.reporter.FlowStep} record, so
    // there's no longer a need to flatten into
    // {@code List<String>}. The reporter's
    // {@link org.plumb.reporter.NdjsonEmitter} and
    // {@link org.plumb.reporter.FindingFormatter} both
    // call {@code FlowStep.toCanonicalString()} when
    // they need the legacy string form.
    List<org.plumb.reporter.FlowStep> steps = uf.flowSteps();
    // Pull the sink function signature from the
    // last flow step (the "sink" or "violation" step).
    // The function IS its own signature, in the canonical
    // "Class#method(args):ret" form. The signature is the
    // single field; consumers that need just the method
    // name can split at '#' and strip from '(' onward.
    String sinkFunction = null;
    if (!uf.flowSteps().isEmpty()) {
      org.plumb.reporter.FlowStep last = uf.flowSteps().get(uf.flowSteps().size() - 1);
      sinkFunction = last.functionSig() == null ? "" : last.functionSig();
    }
    return new Finding(
        file,
        uf.line(),
        uf.column(),
        def,
        uf.message(),
        convertFindingSeverity(uf.severity()),
        uf.flow(),
        steps,
        null,
        null,
        sinkFunction,
        // P3 PATCH: thread the v2 trace
        // through convertUnifiedFinding so the
        // emit pipeline (NdjsonEmitter,
        // SARIF v2 extension) sees the DAG.
        // uf.trace() is the post-rewrite
        // instance carrying the side-channel
        // trace attached by
        // FindingReporter.reportWithTrace.
        uf.trace());
  }

  private static RuleDef.Severity convertRuleDefSeverity(org.plumb.rules.RuleDef.Severity s) {
    return switch (s) {
      case INFO -> RuleDef.Severity.INFO;
      case LOW -> RuleDef.Severity.LOW;
      case MEDIUM -> RuleDef.Severity.MEDIUM;
      case HIGH -> RuleDef.Severity.HIGH;
      case CRITICAL -> RuleDef.Severity.CRITICAL;
    };
  }

  private static RuleDef.Severity convertFindingSeverity(org.plumb.rules.RuleDef.Severity s) {
    return switch (s) {
      case INFO -> RuleDef.Severity.INFO;
      case LOW -> RuleDef.Severity.LOW;
      case MEDIUM -> RuleDef.Severity.MEDIUM;
      case HIGH -> RuleDef.Severity.HIGH;
      case CRITICAL -> RuleDef.Severity.CRITICAL;
    };
  }

  /**
   * bag of per-file parse/translate/style results.
   *
   * <p>It does NOT hold the parse tree. It used to, and that was the single biggest reason a scan
   * ran out of heap: every file's whole AST stayed reachable from here until the parse phase
   * finished, so a scan of a few thousand files held a few thousand parse trees at once. Nothing
   * ever read the tree back -- the style checks run while the file is being translated -- so it is
   * not kept.
   */
  record TranslatedFile(
      Path file, org.plumb.tac.ir.TacProgram program, List<Finding> styleFindings) {}

  /**
   * What happened to one file in the parallel parse phase.
   *
   * <p>Three things can happen to a file, and they are NOT the same thing:
   *
   * <ul>
   *   <li>it was translated, and {@code translated} holds the result;
   *   <li>it could not be translated -- a broken file, or one that ran the heap out. The
   *       translation step logged it and recorded it as unanalysed, so the caller must not try it
   *       again;
   *   <li>it was DEFERRED -- the heap guard refused to start it, or the worker ran out of memory.
   *       Nothing was translated and nothing was recorded, so the caller has to do the work itself.
   * </ul>
   *
   * <p>The worker used to answer {@code null} for all three, and the caller could not tell them
   * apart: a deferred file was dropped without a trace.
   */
  private record Phase1Outcome(TranslatedFile translated, boolean retryOnCaller) {

    /** The file was translated, or failed in a way the translation step already recorded. */
    static Phase1Outcome done(TranslatedFile translated) {
      return new Phase1Outcome(translated, false);
    }

    /** The file could not be translated; the translation step recorded it. */
    static Phase1Outcome failed() {
      return new Phase1Outcome(null, false);
    }

    /** The file was not attempted; the caller has to do it. */
    static Phase1Outcome deferred() {
      return new Phase1Outcome(null, true);
    }
  }

  private List<Finding> runStyleChecks(
      ParsedSource ps,
      Path file,
      RuleRegistry rules,
      List<org.plumb.style.AstChecker> styleChecks) {
    // A1: single-pass group via AstDispatcher. The
    // dispatcher walks the CompilationUnit once and
    // dispatches each node to every registered
    // AstChecker.
    // pass the per-rule parameter
    // overrides loaded from `checks.<rule>.params.<name>`
    // into the StyleContext. Each checker's `init()`
    // will then set itself as the current rule on the
    // context (see `sctx.setCurrentRule(c.name())` below)
    // so per-checker lookups scope by rule id.
    StyleContext sctx =
        new StyleContext(ps, rules, config.ruleParams, staticMethodCache, staticFieldValues);
    org.plumb.style.AstDispatcher d = new org.plumb.style.AstDispatcher();
    for (org.plumb.style.AstChecker c : styleChecks) {
      if (c.name().equals("method-length")) continue;
      // R-PATCH v2: skip style checkers whose id is not
      // allowed under the current whitelist / blacklist.
      // The checker instance's name() is its rule id (e.g.
      // "STYLE_EMPTY_CATCH"); mirror the registry-based
      // filter in buildRegistry() and the DFA filter in
      // runUnifiedCheckers().
      //
      // C3: also accept the checker's class simple
      // name (e.g. "SystemExitUsageCheck") as a whitelist
      // key. Some AST checkers don't expose their RuleId
      // via name() — the AST interface only requires a
      // "human-readable name" — so the whitelist entry
      // for them is the class name. Accept either form.
      if (!isCheckerAllowed(c)) {
        // The DSL checker serves MANY rules, each with its own id.
        // Gating it on the checker's own name ("dsl-kotlin") would
        // drop every DSL rule in whitelist mode, because no
        // whitelist entry can name a checker that stands for a
        // whole rule set.
        //
        // So the DSL checker is admitted when at least one of its
        // rules is allowed; the per-rule decision is made when the
        // rule reports, against the rule's own id.
        if (!(c instanceof org.plumb.dsl.kotlin.DslAstChecker dsl)) continue;
        boolean anyAllowed = false;
        for (RuleDef def : dsl.ruleDefs()) {
          String id = def.ruleId().id();
          if (isRuleIdAllowed(id) || isRuleIdAllowed(def.ruleId().name())) {
            anyAllowed = true;
            break;
          }
        }
        if (!anyAllowed) continue;
      }
      d.register(c);
    }
    try {
      d.run(sctx);
    } catch (Throwable ex) {
      LOG.warn("Style dispatcher failed for {}: {}", file, ex);
    }
    // R-PATCH v2: also filter after-style findings by rule
    // id (defensive — should be a no-op given the loop above
    // but mirrors the DFA filter exactly).
    //
    // C3: also accept the rule_id.name() and the
    // class simple-name so AST checkers whose
    // DEFAULT_RULE ruleId is not in the whitelist but
    // whose name / class name IS in the whitelist
    // still emit findings.
    List<Finding> all = sctx.findings();
    if (config.whitelistMode && !config.enabledRuleNames.isEmpty()) {
      List<Finding> kept = new ArrayList<>(all.size());
      for (Finding f : all) {
        String id = f.rule().ruleId().id();
        String name = f.rule().ruleId().name();
        String checkerClass = f.rule().checkerClass();
        String clsSimple =
            checkerClass == null ? null : checkerClass.substring(checkerClass.lastIndexOf('.') + 1);
        // The SAME predicate the registration gate uses, so the two can
        // never disagree. The previous code compared the whitelist entries
        // by hand and had its own idea of what a "bare id" is (it stripped
        // at the FIRST dot, so `ast-bug-incorrect-return-comparison` became
        // `bug-incorrect-return-comparison`), which is a different
        // normalisation from the one the CLI's `--rule` filter uses. A
        // finding could therefore pass the registration gate and be dropped
        // here, or the other way round.
        //
        // The class simple name is accepted with and without its `Check`
        // suffix, because the per-CWE configs were written against the
        // shorter form.
        if (findingIsAllowed(f, config.enabledRuleNames)) {
          kept.add(f);
        }
      }
      return kept;
    }
    return all;
  }

  /**
   * Step 7 — the list of {@link org.plumb.style.AstChecker}s the runner uses for AI dedup. Built
   *
   * <p>once and shared with {@link #runStyleChecks(ParsedSource, Path, RuleRegistry, List)} so the
   * AI
   *
   * <p>dedup pass can look up the rule prompt for each style finding by its rule id.
   */
  private List<org.plumb.style.AstChecker> buildStyleChecks() {
    List<org.plumb.style.AstChecker> out = new ArrayList<>();
    for (org.plumb.style.AstChecker c : new AstCheckerRegistry().builtins().all()) {
      if (c.name().equals("method-length")) continue;
      out.add(c);
    }
    out.add(new org.plumb.style.style.MethodTooLongCheck());
    // The AST rules the user supplies through --dsl-rules / the YAML's
    // `dsl.rules` block. They are compiled once, here, so the cost is
    // paid once per scan rather than once per file. When no rule file is
    // given, nothing is added and the scan is unaffected.
    //
    // The checker itself was already built by buildDslChecker(),
    // which runs before buildRegistry() so the rules could be
    // registered in the live registry.
    if (dslChecker != null) {
      out.add(dslChecker);
    }
    return out;
  }

  /**
   * The rule id -> CWE map, read from the rules THIS scan can actually emit.
   *
   * <p>The map is built from the same two lists the scan itself uses: the AST checkers ({@link
   * #buildStyleChecks()}) and the DFA checkers ({@link #buildDfaCheckers()}). Reading it from the
   *
   * <p>engine -- rather than from a table maintained beside the engine -- is the whole point. A
   * table
   *
   * <p>maintained beside the engine drifts, and a Juliet measurement that attributes a case to a
   * CWE
   *
   * <p>the engine does not emit is measuring a different tool than the one that ships.
   *
   * <p>The AST side contributes the rule id and the CWE list its own {@code RuleDef} declares. The
   *
   * <p>DFA side contributes the rule id and the single CWE its own {@code TaintRule} declares.
   *
   * <p>A rule id that appears on both sides is reported with BOTH sets of CWEs, because the engine
   *
   * <p>really does emit that id from two different checkers with two different CWEs. Merging them
   *
   * <p>would hide a real fact: a finding with that id does not have one CWE, it has two, and which
   * one
   *
   * <p>it is depends on which checker produced it.
   *
   * @return rule id -> the CWEs the engine emits under that id, sorted
   */
  java.util.Map<String, java.util.List<String>> liveRuleCweMap() {
    java.util.Map<String, java.util.Set<String>> map = new java.util.TreeMap<>();
    // The AST side. Each checker declares its own RuleDef, which carries
    // the rule id and the CWE list.
    //
    // The id is `RuleDef.ruleId().id()` -- the EMITTED id -- and not
    // `name()`. A RuleId has two names: `id()` is what the finding
    // carries (e.g. "ast-security-unchecked-return") and `name()` is the
    // legacy registry name (e.g. "UNCHECKED_RETURN"). The finding's
    // rule_id is the `id()`, so the map has to use the `id()`: a map
    // keyed by the legacy name would not contain a single id the report
    // actually prints.
    for (org.plumb.style.AstChecker checker : buildStyleChecks()) {
      try {
        // allRules(), not rule(): a few checkers emit SEVERAL rules
        // under different ids and different CWEs, and listing only the
        // first would leave the rest of that checker's rules out of the
        // map -- which is exactly how a rule ends up with a guessed CWE.
        for (org.plumb.rules.RuleDef def : checker.allRules()) {
          if (def == null) continue;
          String id = def.ruleId().id();
          if (id == null || id.isBlank()) continue;
          map.computeIfAbsent(id, k -> new java.util.TreeSet<>()).addAll(def.cwe());
        }
      } catch (RuntimeException ex) {
        // A checker whose rule metadata cannot be read is skipped rather
        // than aborting the map: the map is a report, and one broken
        // checker must not take the whole report down.
      }
    }
    // The DFA side. The taint-based checkers expose their rule; the
    // control-flow ones declare one themselves.
    for (org.plumb.dfa.engine.DfaChecker checker : buildDfaCheckers()) {
      try {
        org.plumb.dfa.taint.TaintRule rule =
            (checker instanceof org.plumb.dfa.taint.TaintedCheckerBase)
                ? ((org.plumb.dfa.taint.TaintedCheckerBase) checker).rule()
                : null;
        if (rule != null) {
          String id = rule.ruleId();
          if (id == null || id.isBlank()) continue;
          if (rule.cwe() != null && !rule.cwe().isBlank()) {
            // A rule may declare more than one CWE for the same defect
            // class. The map lists every one of them, so a corpus that
            // numbers the defect differently still finds the rule.
            map.computeIfAbsent(id, k -> new java.util.TreeSet<>()).addAll(cweList(rule.cwe()));
          } else {
            map.computeIfAbsent(id, k -> new java.util.TreeSet<>());
          }
          continue;
        }
        // The checkers that build their finding inline declare their own
        // rule metadata.
        org.plumb.rules.RuleDef def = checker.ruleDef();
        if (def == null) continue;
        String id = def.ruleId().id();
        if (id == null || id.isBlank()) continue;
        map.computeIfAbsent(id, k -> new java.util.TreeSet<>()).addAll(def.cwe());
      } catch (RuntimeException ex) {
        // As above: one unreadable checker does not abort the map.
      }
    }
    java.util.Map<String, java.util.List<String>> out = new java.util.TreeMap<>();
    for (java.util.Map.Entry<String, java.util.Set<String>> e : map.entrySet()) {
      out.put(e.getKey(), new java.util.ArrayList<>(e.getValue()));
    }
    return out;
  }

  /**
   * Build the DSL checker for this scan, if the user supplied any rule file.
   *
   * <p>The result is stored in {@link #dslChecker} so that both the check list and the rule
   *
   * <p>registry can see the same instance. Building it twice would compile every rule twice.
   *
   * <p>The DSL rules are AST rules only. Data-flow and taint rules are configured in {@code
   * plumb-dfa}'s YAML files and are not affected by this.
   */
  private void buildDslChecker() {
    if (config == null) return;
    List<java.nio.file.Path> files = config.dslRuleFiles;
    if (files == null || files.isEmpty()) return;
    try {
      dslChecker = org.plumb.dsl.kotlin.DslAstChecker.Companion.from(files, config.dslStrict);
    } catch (RuntimeException ex) {
      // In strict mode a broken rule aborts the scan; otherwise the DSL
      // checker logs and skips the broken rule.
      if (config.dslStrict) {
        throw ex;
      }
      LOG.warn("Failed to load the DSL rule files {}: {}", files, ex.toString());
    }
  }

  /**
   * The DSL checker built for this scan, or null when the user supplied no DSL rule file.
   *
   * <p>It is kept here because the same instance has to be visible in two places: the check list
   *
   * <p>passed to the dispatcher, and the rule registry that {@link
   * org.plumb.style.StyleContext#report} consults.
   */
  private org.plumb.dsl.kotlin.DslAstChecker dslChecker;

  private RuleRegistry buildRegistry() {
    // the rule catalog is now
    // composed of two layers:
    //
    // 1. The 115 hard-coded legacy entries from
    //    {@link RuleRegistry#withBuiltinDefaults()}
    //    cover the DFA checkers (~25) and the early-
    //    round taint / style / spotbugs-style rules
    //    (~90). These stay as-is for back-compat —
    //    external YAML configs and CI dashboards
    //    still reference the legacy rule ids.
    //
    // 2. Every {@link org.plumb.style.AstChecker}
    //    registered via
    //    {@link org.plumb.style.AstCheckerRegistry}
    //    contributes its own {@link RuleDef} via
    //    {@link AstChecker#rule()} (which reflects
    //    the checker's `DEFAULT_RULE` / `RULE`
    //    static field). The result is mirrored into
    //    the rule catalog by
    //    {@link RuleRegistry#registerFromChecker(Object)}.
    //
    // For duplicates (a checker whose id is already
    // present in the legacy table) the AST checker's
    // own rule metadata wins because the
    // implementation file is the single source of
    // truth for that checker.
    RuleRegistry r = new RuleRegistry().withBuiltinDefaults();
    // Layer 2: every AST checker in the builtins set.
    r.registerFromCheckers(new org.plumb.style.AstCheckerRegistry().builtins().all());
    // Layer 3: the user-supplied DSL rules.
    //
    // A DSL rule is not an AstChecker -- one checker serves many rules --
    // so registerFromCheckers() cannot pick it up. Register the rule
    // metadata directly instead, so the DSL rules take part in the
    // whitelist / blacklist filters and in StyleContext.report()'s
    // enabled gate exactly like every built-in rule.
    if (dslChecker != null) {
      for (RuleDef def : dslChecker.ruleDefs()) {
        try {
          r.register(def);
        } catch (RuntimeException ex) {
          // A duplicate id means another rule already claims this
          // id. Log it and keep the first one rather than
          // failing the whole scan.
          LOG.warn("Could not register the DSL rule '{}': {}", def.ruleId().id(), ex.toString());
        }
      }
    }
    boolean whitelistActive = config.whitelistMode && !config.enabledRuleNames.isEmpty();
    for (RuleDef def : r.all()) {
      // the checker's own DEFAULT_RULE.enabled
      // is the new low-water default. If the checker
      // declared disabled-by-default (the Juliet-driven
      // policy decision from ), start with enabled
      // = false here. The user's yaml rules.enabled /
      // rules.disabled list can still flip it back on.
      boolean enabled = def.enabled();
      String id = def.ruleId().id();
      String name = def.ruleId().name();
      // The rule has several identities and a filter entry may name any of
      // them, so the SAME predicate the per-finding filter uses decides
      // here. The previous code compared the entries by hand against three
      // hand-computed spellings of the id, and its idea of a "bare id" was
      // different from the per-finding filter's — so a rule could be
      // enabled here and dropped there, or the other way round.
      //
      // The checker class name is included because the per-CWE configs were
      // written against the class name (e.g. `UncheckedReturn`).
      String clsSimple = def.checkerClass();
      if (clsSimple != null) {
        int dot = clsSimple.lastIndexOf('.');
        clsSimple = dot >= 0 ? clsSimple.substring(dot + 1) : clsSimple;
      }
      boolean disabled =
          filterMatches(name, config.disabledRuleNames)
              || filterMatches(id, config.disabledRuleNames)
              || (clsSimple != null && filterMatches(clsSimple, config.disabledRuleNames));
      for (String alias : def.aliases()) {
        if (filterMatches(alias, config.disabledRuleNames, true)) {
          disabled = true;
        }
      }
      if (disabled) {
        enabled = false;
      }
      boolean explicitlyEnabled =
          filterMatches(name, config.enabledRuleNames)
              || filterMatches(id, config.enabledRuleNames)
              || (clsSimple != null && filterMatches(clsSimple, config.enabledRuleNames));
      for (String alias : def.aliases()) {
        if (filterMatches(alias, config.enabledRuleNames, true)) {
          explicitlyEnabled = true;
        }
      }
      if (explicitlyEnabled) {
        enabled = true;
      }
      // In whitelist mode a rule that no entry names stays off, unless the
      // user named it explicitly above.
      if (whitelistActive && !explicitlyEnabled) {
        enabled = false;
      }
      if (enabled != def.enabled()) {
        r.updateInPlace(def.withEnabled(enabled));
      }
    }
    return r;
  }

  private List<Path> expandInputs(Path input) throws IOException {
    if (Files.isDirectory(input)) {
      try (Stream<Path> walk = Files.walk(input)) {
        return walk.filter(FrontendRegistry::isSourceFile)
            // The language filter, applied at the discovery level: a file whose language was
            // not selected is never opened, never translated, and never reaches a frontend.
            .filter(p -> languageFilter().includes(FrontendRegistry.languageOf(p)))
            // Skip files under
            // `src/test/` (Java's test
            // directory convention). The
            // previous filter
            // `!path.contains("test\")`
            // was too aggressive —  it
            // excluded any path whose
            // name happened to contain
            // `test` (e.g.
            // `smoke_petclinic\` in
            // the prior smoke test).
            .filter(p -> !isUnderTestDirectory(p))
            .toList();
      }
    }
    return List.of(input);
  }

  /**
   * →cross-TU as default. If the user requested a single file but its parent directory has a small
   *
   * <p>number of sibling .java files, include them in the merged scan so the DFA engine can follow
   *
   * <p>calls across files (Variant 42 / 45 / 54 helpers in Juliet test suites live as separate
   * files
   *
   * <p>in the same directory).
   *
   * <p>Threshold: cap the sibling count at {@link #CROSS_TU_AUTO_EXPAND_LIMIT}. Beyond that the
   *
   * <p>directory is clearly a large test corpus — better to let the user opt in by passing the
   *
   * <p>directory as the input than to silently run a 1000-file scan per single-file command.
   *
   * <p>Returns the original list unchanged if there's no opportunity to expand (single file with no
   *
   * <p>siblings, or already a multi-file request).
   */
  private static final int CROSS_TU_AUTO_EXPAND_LIMIT = 20;

  private List<Path> maybeExpandForCrossTu(List<Path> original) {
    if (original.size() != 1) return original;
    Path single = original.get(0);
    Path parent = single.getParent();
    if (parent == null) return original;
    try (Stream<Path> walk = Files.list(parent)) {
      List<Path> siblings =
          walk.filter(FrontendRegistry::isSourceFile)
              .filter(p -> !p.equals(single))
              .filter(this::matches)
              .filter(p -> !isUnderTestDirectory(p))
              // The language filter applies to the siblings too. Without this, a
              // `--language kotlin` run that was pointed at one Kotlin file would pull in the
              // Java files next to it, and the Java findings would appear in a Kotlin-only
              // scan.
              .filter(p -> languageFilter().includes(FrontendRegistry.languageOf(p)))
              .toList();
      if (siblings.isEmpty()) return original;
      if (siblings.size() > CROSS_TU_AUTO_EXPAND_LIMIT) {
        // Large directory —  fall back to
        // single-file mode. The user
        // should pass the directory
        // explicitly to enable
        // cross-TU for a corpus scan.
        LOG.debug(
            "R713 cross-TU auto-expand skipped: {} sibling(s) > {} limit for {}",
            siblings.size(),
            CROSS_TU_AUTO_EXPAND_LIMIT,
            parent);
        return original;
      }
      List<Path> merged = new ArrayList<>();
      merged.add(single);
      for (Path s : siblings) merged.add(s);
      LOG.info(
          "R713 cross-TU auto-expand: {} + {} sibling(s) under {}",
          single.getFileName(),
          siblings.size(),
          parent);
      return merged;
    } catch (IOException e) {
      LOG.debug("R713 cross-TU discovery failed for {}: {}", parent, e.getMessage());
      return original;
    }
  }

  private boolean matches(Path file) {
    String s = file.toString().replace('\\', '/');
    for (String ex : config.excludeGlobs) {
      if (s.contains(ex)) return false;
    }
    if (config.includeGlobs.isEmpty()) return true;
    for (String in : config.includeGlobs) {
      if (s.contains(in)) return true;
    }
    return false;
  }

  private List<Finding> scanFile(
      Path file,
      RuleRegistry rules,
      List<org.plumb.dfa.engine.DfaChecker> dfaCheckers,
      List<org.plumb.style.AstChecker> styleChecks) {
    List<Finding> out = new ArrayList<>();
    // A non-Java file has no JavaParser AST. It goes through the frontend
    // path, which produces the TAC and runs the dataflow checkers over it --
    // and no style findings, because the style checkers are JavaParser AST
    // checkers.
    if (FrontendRegistry.languageOf(file) != org.plumb.tac.ir.SourceLanguage.JAVA) {
      return scanNonJavaFile(file, dfaCheckers);
    }
    ParsedSource ps;
    try {
      ps = buildParser().parse(file);
    } catch (Exception e) {
      LOG.warn("Parse failure for {}: {}", file, e.getMessage());
      // The file is in the project and was not analysed. It is recorded so the
      // report can say so: a file that produces no findings and a file that was
      // never read look the same otherwise.
      unparsedFiles.add(file.toString());
      return out;
    }
    org.plumb.tac.ir.TacProgram program = null;
    if (config.runTaint
        || config.runDivByZero
        || config.runResourceLeak
        || config.runNullDeref
        || config.runHardcodedCred
        || config.runIntOverflow
        || config.runArrayBounds
        || config.runWeakCrypto
        || config.runInsecureRandom
        || config.runPredictableSeed
        || config.runPredictableKey
        || config.runStaticApiKey
        || config.runInsecureComms
        || config.runCorsMisconfig
        || config.runInsecureCookie
        || config.runBadShift
        || config.runInfiniteLoop
        || config.runConstantCondition
        || config.runSensitiveDataLeak
        || config.runFieldHardcodedPassword
        || config.runMissingAuthorization
        || config.runMissingInputValidation
        || config.runInformationExposure
        || config.runErrorMessageInfoLeak
        || config.runCommentInfoLeak
        || config.runUncheckedReturn
        || config.runIncorrectReturn
        || config.runThreadRun
        || config.runStringEquality
        || config.runCatchGeneric
        || config.runObsoleteFunction
        // Any of the 7 unified
        // checkers also needs the
        // translated TAC program.
        || config.runTryCatchFinally
        || config.runOffByOne
        || config.runEqualsCalledOnNull
        || config.runCompareWithSelf
        || config.runXxe
        || config.runReturnFromFinally
        || config.runEmptyCatchHandler
        // 2 additional unified checkers also need translated TAC
        || config.runIteratorConcurrentModification
        || config.runCollectionSelfReference
        // Cross-function uncaught exception also needs TAC
        || config.runUncaughtExceptionPropagator
        // 3 unified taint-style checkers
        || config.runUncheckedLoopCondition
        || config.runResourceExhaustion
        || config.runUncontrolledMemAlloc
        || config.runNumericTruncation
        // new DfaChecker port from plumb-dfa
        || config.runBigDecimalDoubleConstructor) {
      try {
        program = translateJava(ps, file);
      } catch (Exception e) {
        LOG.warn("TAC translation failure for {}: {}", file, e.getMessage());
      }
    }
    // Run the plumb-dfa
    // engine for the 7 DFA-only checkers
    // (TryCatchFinally, OffByOne, EqualsCalledOnNull,
    // CompareWithSelf, Xxe, ReturnFromFinally,
    // EmptyCatchHandler). A later round added the 2
    // DFA-only checkers (IteratorConcurrentModification,
    // CollectionSelfReference). Another round added the
    // cross-function UncaughtExceptionPropagator.
    // All plumb-dfa rules were ported to
    // plumb-dfa (DfaChecker + TaintedCheckerBase);
    // the legacy plumb-dfa module was removed.
    // For the single-file path the
    // TacProgram has its own sourcePath()
    // so the engine's own fallback
    // registers it. We pass an empty map
    // (no merged sources) to keep the
    // engine's logic uniform.
    if (program != null) {
      // seed the functionSourcePaths
      // map with the single file's path so
      // DfaEngine.withFunctionSourcePaths()
      // can index a StaticFieldResolver
      // against the file's project root.
      // Without this the Juliet
      // single-file run skips
      // StaticFieldResolver.init()
      // (empty map — no SourcePath walk)
      // and FIELDLOADs on class
      // references fall through to the
      // orphan-SOURCE shape.
      java.util.Map<String, java.nio.file.Path> singleMap = new java.util.HashMap<>();
      java.nio.file.Path sp = program.sourcePath();
      if (sp != null) {
        for (org.plumb.tac.ir.TacFunction fn : program.functions()) {
          String enc = fn.enclosingClass();
          if (enc != null && !enc.isEmpty()) {
            singleMap.put(enc, sp);
          }
        }
      }
      out.addAll(runUnifiedCheckers(null, program, java.util.Map.copyOf(singleMap), dfaCheckers));
    }
    if (config.runStyle) {
      // A1: single-pass group via AstDispatcher.
      // pass per-rule param overrides
      // (see the parallel runStyleChecks() above).
      StyleContext sctx =
          new StyleContext(ps, rules, config.ruleParams, staticMethodCache, staticFieldValues);
      org.plumb.style.AstDispatcher d = new org.plumb.style.AstDispatcher();
      // Use the SHARED check list, not a freshly built one.
      //
      // The shared list is what buildStyleChecks() produced, and
      // it is the only list that contains the user's DSL rules
      // (the DslAstChecker). Rebuilding the list here from
      // AstCheckerRegistry.builtins() would silently drop every
      // DSL rule on the single-file path -- the multi-file path
      // and the single-file path would then disagree about
      // which rules ran.
      for (org.plumb.style.AstChecker c : styleChecks) {
        if (c.name().equals("method-length")) continue;
        // R-PATCH v2: skip style checkers whose id is not
        // allowed under the current whitelist /
        // blacklist. Same filter as runStyleChecks().
        //
        // C3: also accept the checker's class
        // simple name as a whitelist key. Some AST
        // checkers don't expose their RuleId via
        // name(); the whitelist entry for them is
        // the class name.
        //
        // The checker's RULE ID is accepted too, and it has to be: the
        // rule id is what a finding carries and what the NDJSON output
        // prints, so a user who copies an id out of the report and
        // passes it back to `--rule` has to get that rule back. Before
        // this, the AST registration gate only looked at the checker's
        // NAME and its CLASS NAME, so `--rule ast-bug-incorrect-return-comparison`,
        // the exact id in the report, selected nothing at all.
        if (!isCheckerAllowed(c)) {
          // The DSL checker serves MANY rules, so it cannot be
          // gated on its own name. Admit it when at least one
          // of its rules is allowed; the per-rule decision is
          // made at report time.
          if (!(c instanceof org.plumb.dsl.kotlin.DslAstChecker dsl)) continue;
          boolean anyAllowed = false;
          for (RuleDef def : dsl.ruleDefs()) {
            if (isRuleIdAllowed(def.ruleId().id()) || isRuleIdAllowed(def.ruleId().name())) {
              anyAllowed = true;
              break;
            }
          }
          if (!anyAllowed) continue;
        }
        d.register(c);
      }
      if (isRuleIdAllowed("method-length")) {
        d.register(new org.plumb.style.style.MethodTooLongCheck());
      }
      try {
        d.run(sctx);
      } catch (Throwable ex) {
        LOG.warn("Style dispatcher failed for {}: {}", file, ex);
      }
      // R-PATCH v2: also filter after-style findings by
      // rule id (defensive; mirrors the DFA filter).
      //
      // C3: also accept the rule_id.name() and the
      // class simple-name so AST checkers whose
      // DEFAULT_RULE ruleId is not in the whitelist but
      // whose name / class name IS in the whitelist
      // still emit findings.
      List<Finding> styleOut = sctx.findings();
      if (config.whitelistMode && !config.enabledRuleNames.isEmpty()) {
        List<Finding> kept = new ArrayList<>(styleOut.size());
        for (Finding f : styleOut) {
          String id = f.rule().ruleId().id();
          String name = f.rule().ruleId().name();
          String checkerClass = f.rule().checkerClass();
          String clsSimple =
              checkerClass == null
                  ? null
                  : checkerClass.substring(checkerClass.lastIndexOf('.') + 1);
          // The SAME predicate the registration gate uses, so the two can
          // never disagree. The previous code compared the whitelist
          // entries by hand and had its own idea of what a "bare id" is
          // (it stripped at the FIRST dot, so
          // `ast-bug-incorrect-return-comparison` became
          // `bug-incorrect-return-comparison`), which is a different
          // normalisation from the one the CLI's `--rule` filter uses.
          // A finding could therefore pass the registration gate and be
          // dropped here, or the other way round.
          //
          // The class simple name is accepted with and without its
          // `Check` suffix, because the per-CWE configs were written
          // against the shorter form.
          if (findingIsAllowed(f, config.enabledRuleNames)) {
            kept.add(f);
          }
        }
        styleOut = kept;
      }
      out.addAll(styleOut);
    }
    return out;
  }

  /**
   * The single-file path for a non-Java source file.
   *
   * <p>The frontend lowers the file to TAC, and the dataflow checkers run over that TAC. There are
   *
   * <p>no style findings: the style checkers walk a JavaParser AST, and this file has none.
   *
   * @param file the source file
   * @param dfaCheckers the checkers to run
   * @return the findings
   */
  private List<Finding> scanNonJavaFile(
      Path file, List<org.plumb.dfa.engine.DfaChecker> dfaCheckers) {
    List<Finding> out = new ArrayList<>();
    TranslatedFile translated = translateNonJava(file);
    if (translated == null) {
      return out;
    }
    org.plumb.tac.ir.TacProgram program = translated.program();
    if (program == null) {
      return out;
    }
    java.util.Map<String, java.nio.file.Path> singleMap = new java.util.HashMap<>();
    java.nio.file.Path sp = program.sourcePath();
    if (sp != null) {
      for (org.plumb.tac.ir.TacFunction fn : program.functions()) {
        String enc = fn.enclosingClass();
        if (enc != null && !enc.isEmpty()) {
          singleMap.put(enc, sp);
        }
      }
    }
    out.addAll(runUnifiedCheckers(null, program, java.util.Map.copyOf(singleMap), dfaCheckers));
    return out;
  }

  /**
   * The completeness record for this scan: what it did NOT do.
   *
   * <p>Six things can make an analysis partial, and all six are recorded:
   *
   * <ul>
   *   <li>a <b>budget</b> that ran out -- the analysis stopped early on purpose;
   *   <li>a <b>module scope</b> -- findings whose sink was in another module were not reported;
   *   <li>a <b>heap reduction</b> -- the analysis pool was shrunk, which is safe, but the user
   *       should know the scan ran under pressure;
   *   <li><b>files that could not be parsed</b> -- they are in the project and they were not
   *       analysed, so their findings are not in the report;
   *   <li><b>files that ran the machine out of memory</b> -- the same, for a different reason:
   *       those files are fine, and the machine was not big enough to look at them;
   *   <li>an <b>interrupted scan</b> -- the files the parse phase never reached.
   * </ul>
   *
   * <p>The record is a claim about the ANALYSIS, not about the code. It may only make the report
   * more cautious; it may never be used to claim that code is safe.
   */
  private org.plumb.reporter.AnalysisCompleteness completenessRecord() {
    List<String> notes = new ArrayList<>();
    if (budgetReport != null && !budgetReport.complete()) {
      notes.add("the analysis budget ran out: " + budgetReport.summary());
    }
    if (moduleScopeReport != null && moduleScopeReport.isLimited()) {
      if (moduleScopeReport.outOfScopeFindings() > 0) {
        notes.add(
            moduleScopeReport.outOfScopeFindings()
                + " finding(s) whose sink was outside module "
                + moduleScopeReport.module()
                + " were not reported");
      }
      if (moduleScopeReport.crossingCallers() > 0) {
        notes.add(
            moduleScopeReport.crossingCallers()
                + " caller(s) in module "
                + moduleScopeReport.module()
                + " called out of the module; the taint crossed the boundary and the crossings are"
                + " recorded");
      }
    }
    if (heapGuard.hasReduced()) {
      notes.add(
          "the heap came under pressure, so the analysis pool was reduced: " + heapGuard.reason());
    }
    if (!unparsedFiles.isEmpty()) {
      List<String> unparsed = snapshotOf(unparsedFiles);
      if (!unparsed.isEmpty()) {
        notes.add(
            unparsed.size()
                + " file(s) could not be parsed and were not analysed: "
                + summarise(unparsed));
      }
    }
    if (!outOfMemoryFiles.isEmpty()) {
      List<String> starved = snapshotOf(outOfMemoryFiles);
      if (!starved.isEmpty()) {
        notes.add(
            starved.size()
                + " file(s) ran the machine out of memory and were not analysed: "
                + summarise(starved));
      }
    }
    if (interruptedFiles > 0) {
      notes.add(
          "the scan was interrupted before "
              + interruptedFiles
              + " file(s) were parsed, so they were not analysed");
    }
    if (notes.isEmpty()) return org.plumb.reporter.AnalysisCompleteness.allComplete();
    return org.plumb.reporter.AnalysisCompleteness.incomplete(notes);
  }

  /**
   * A copy of a list the parse workers may still be writing to.
   *
   * <p>The workers are done by the time the completeness record is built, but the record must not
   * depend on that: it reads the list once and works on the copy, so a file that arrives late
   * cannot make the count and the names disagree.
   *
   * @param list the list to copy
   * @return the copy
   */
  private static List<String> snapshotOf(List<String> list) {
    synchronized (list) {
      return new ArrayList<>(list);
    }
  }

  /**
   * The files the scan could not look at because they could not be parsed.
   *
   * <p>Package-private so that a test can check the record. The record is the point of the fix: a
   * file that was not analysed has to be visible somewhere, and this list is where it becomes
   * visible to the user.
   *
   * @return the files that could not be parsed
   */
  List<String> unparsedFiles() {
    return snapshotOf(unparsedFiles);
  }

  /**
   * The files the scan did not look at because the machine ran out of memory.
   *
   * @return the files that ran the machine out of memory
   */
  List<String> outOfMemoryFiles() {
    return snapshotOf(outOfMemoryFiles);
  }

  /** A short list of the names, for a note that has to stay readable. */
  private static String summarise(List<String> names) {
    int shown = Math.min(3, names.size());
    String head = String.join(", ", names.subList(0, shown));
    if (names.size() > shown) {
      return head + " and " + (names.size() - shown) + " more";
    }
    return head;
  }

  private void emit(List<Finding> findings) throws IOException {
    // The completeness record. It says what the analysis did NOT do, so that
    // "no findings" is never mistaken for "the analysis looked everywhere and
    // found nothing".
    org.plumb.reporter.AnalysisCompleteness completeness = completenessRecord();
    if (!completeness.complete()) {
      LOG.warn("the analysis was not complete: {}", completeness.summary());
    }
    if (config.outputFormat == ScanConfig.OutputFormat.NDJSON) {
      Writer w =
          (config.outputPath != null)
              ? Files.newBufferedWriter(config.outputPath, StandardCharsets.UTF_8)
              : new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
      try {
        new NdjsonEmitter(w).emit(findings);
      } finally {
        // The stdout branch MUST flush: the writer buffers, and
        // SastMain calls System.exit() right after run() returns,
        // which discards whatever is still buffered. See the
        // console branch below for the full note.
        if (config.outputPath != null) {
          w.close();
        } else {
          w.flush();
        }
      }
    } else if (config.outputFormat == ScanConfig.OutputFormat.SARIF) {
      // P3.3: SARIF 2.1.0 emitter with v2 trace
      // channel under properties.v2_trace. v1
      // dataflow_steps is rendered as the standard
      // SARIF `codeFlows` for SARIF-aware consumers.
      Writer w =
          (config.outputPath != null)
              ? Files.newBufferedWriter(config.outputPath, StandardCharsets.UTF_8)
              : new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
      try {
        new org.plumb.reporter.SarifEmitter(w, completeness).emit(findings);
      } finally {
        // The stdout branch MUST flush: the writer buffers, and
        // SastMain calls System.exit() right after run() returns,
        // which discards whatever is still buffered. See the
        // console branch below for the full note.
        if (config.outputPath != null) {
          w.close();
        } else {
          w.flush();
        }
      }
    } else {
      Writer w =
          (config.outputPath != null)
              ? Files.newBufferedWriter(config.outputPath, StandardCharsets.UTF_8)
              : new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
      try {
        for (Finding f : findings) {
          w.write(FindingFormatter.formatConsole(f));
          w.write('\n');
        }
      } finally {
        // The console branch MUST flush.
        //
        // `System.out` is a PrintStream, but the code wraps
        // it in an OutputStreamWriter, and that writer buffers.
        // SastMain calls System.exit() right after run()
        // returns, which bypasses the normal JVM shutdown and
        // discards whatever is still in the writer's buffer.
        //
        // The symptom was silent: the last few findings were
        // simply missing from the console output, and the final
        // line was cut mid-string. The NDJSON and SARIF
        // branches were unaffected because their emitters close
        // the writer themselves.
        //
        // Lesson: any writer that is not closed MUST be flushed
        // before the process exits.
        if (config.outputPath != null) {
          w.close();
        } else {
          w.flush();
        }
      }
    }
  }

  /**
   * Returns true if the path is under a Java-style test directory. The check matches {@code
   * src/test/} as a path segment (case-sensitive →this is the standard Java convention and works on
   *
   * <p>both Windows and Linux). The previous filter {@code !path.contains("test\")} was too
   * aggressive
   *
   * <p>→it excluded any path whose name happened to contain {@code test} (e.g. {@code} in an
   * earlier smoke test).
   */
  private static boolean isUnderTestDirectory(Path p) {
    String s = p.toString();
    String sep = java.io.File.separator;
    return s.contains(sep + "src" + sep + "test" + sep)
        || s.contains(sep + "test" + sep + "java" + sep)
        || s.endsWith(sep + "src" + sep + "test")
        || s.endsWith(sep + "test");
  }
}

package org.plumb.main;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Top-level scan configuration. Loaded from a YAML file or built up programmatically by the CLI.
 */
public final class ScanConfig {

  public List<Path> inputs = new ArrayList<>();
  public Path outputPath;
  public OutputFormat outputFormat = OutputFormat.CONSOLE;

  // R-output-dir: when set, the runner creates <outputDir>/cache/ for
  // IR (TAC) and call-graph caches and writes reports under
  // <outputDir>/report/. CacheMode controls read/write of the cache.
  public Path outputDir;
  public CacheMode cacheMode = CacheMode.OFF;

  /**
   * Upper bound on parallel parse/translate workers. 0 = auto (Runtime.availableProcessors() - 1,
   * floor 1).
   */
  public int maxParallelism = 0;

  // external source / classpath inputs for
  // symbol resolution. All three are consumed by
  // JavaSourceParser's TypeSolver only — they NEVER enter the
  // TAC translation pipeline or the SootUp bytecode path.
  // Third-party jars exist solely so the user source can
  // resolve external type references; they are not analyzed
  // for findings themselves.
  /**
   * Project source roots, e.g. an IDE-style checkout root that contains several Maven/Gradle
   * modules. Each source-root is treated as a flat JavaModule in (per-module classpath is future
   * work).
   */
  public List<Path> sourceRoots = new ArrayList<>();

  /**
   * Individual module source roots. In , --module-root and --source-root have identical effect; the
   * alias exists for UX clarity so a multi-module user can write the modules out separately.
   */
  public List<Path> moduleRoots = new ArrayList<>();

  /**
   * Third-party dependency jars / class-folder directories. Passed to JavaParser's TypeSolver as
   * JarTypeSolver instances; never translated to TAC. May be repeated on the CLI; the same list is
   * shared across all modules in .
   */
  public List<Path> classpathJars = new ArrayList<>();

  /**
   * When the user invoked {@code --build-system} (or auto-detection kicked in), this is the {@link
   * org.plumb.build.BuildProject} the adapter produced. {@code ScanRunner.buildModules} prefers the
   * per-module classpath in {@link org.plumb.build.BuildProject#modules()} over the flat {@link
   * #classpathJars} list so a multi-module Maven project correctly scopes compile vs. test
   * classpath.
   */
  public org.plumb.build.BuildProject buildProject;

  /**
   * Call-graph construction strategy passed to the {@code plumb-dfa} engine. Default is {@code
   * "global"} (cross-TU signature lookup) -- the project directive is that analysis must be global,
   * so the TU-local {@code "cha"} / {@code "rta"} / {@code "vta"} / {@code "1cfa"} / {@code "stub"}
   * paths are only used when the user explicitly asks. Accepts the same names {@link
   * org.plumb.dfa.cg.CallGraphStrategyFactory#create} and {@link
   * org.plumb.dfa.cg.CallGraphStrategyFactory#createCrossTu} recognise:
   *
   * <ul>
   *   <li>{@code "global"} — <b>default</b>. Cross-TU resolution via {@link
   *       org.plumb.dfa.cg.GlobalTACStore} + signature lookup. Used by {@link
   *       org.plumb.dfa.engine.DfaEngine#runWithGlobalStore}.
   *   <li>{@code "cha"} -- Class Hierarchy Analysis, fast / conservative (sound over-approximation
   *       of virtual calls). TU-local.
   *   <li>{@code "rta"} -- Rapid Type Analysis, more precise than CHA, slower. TU-local.
   *   <li>{@code "vta"} -- Variable Type Analysis, tracks variable types through assignments, even
   *       more precise than RTA. TU-local.
   *   <li>{@code "1cfa"} -- 1-CFA context-sensitive analysis, distinguishes same-method invocations
   *       from different call sites. TU-local.
   *   <li>{@code "stub"} -- only direct calls, no virtual resolution. Fastest; for tests / debug.
   *       TU-local.
   *   <li>{@code "cached:<name>:<path>"} -- wrap {@code <name>} with a disk cache at {@code
   *       <path>}; cache key is a hash of the source file set.
   * </ul>
   */
  public String cgStrategy = "global"; // R694 T4: VTA was the legacy single-pass default; R761

  // audit flipped it to "global" (cross-TU signature lookup) per the
  // project directive that analysis must be global. RcgStrategyTest
  // line 34 asserts this default.

  public enum CacheMode {
    /** No disk cache: parse, translate, analyze, discard. */
    OFF,
    /** Read cached TAC + call graph if available; never write. */
    READ,
    /** Write the cache after analysis; never re-use. */
    WRITE,
    /** Re-use cache if present, otherwise build and persist. */
    READWRITE
  }

  public boolean runTaint = true;
  public boolean runStyle = true;
  public boolean runDivByZero = true;
  public boolean runResourceLeak = true;
  public boolean runNullDeref = true;
  public boolean runHardcodedCred = true;
  public boolean runIntOverflow = true;
  // CWE-191 integer underflow (Java DFA, parallel to runIntOverflow).
  public boolean runIntUnderflow = true;
  public boolean runArrayBounds = true;
  // crypto & random checkers
  public boolean runWeakCrypto = true;
  public boolean runInsecureRandom = true;
  public boolean runPredictableSeed = true;
  public boolean runPredictableKey = true;
  public boolean runStaticApiKey = true;
  // webapp config checkers
  public boolean runInsecureComms = true;
  public boolean runCorsMisconfig = true;
  public boolean runInsecureCookie = true;
  // defensive checks
  public boolean runBadShift = true;
  public boolean runInfiniteLoop = true;
  public boolean runConstantCondition = true;
  // sensitive data leak
  public boolean runSensitiveDataLeak = true;
  // instance-field hardcoded credential
  public boolean runFieldHardcodedPassword = true;
  // missing authorization on web endpoint
  public boolean runMissingAuthorization = true;
  // missing input validation on web endpoint
  public boolean runMissingInputValidation = true;
  // information exposure on web endpoint (CWE-200)
  public boolean runInformationExposure = true;
  // error-message info leak
  // (CWE-209 / CWE-200). Catches
  // `except.printStackTrace()`,
  // `log.error(e.getMessage())` in
  // web endpoints, etc.
  public boolean runErrorMessageInfoLeak = true;
  // comment-as-info-leak
  // (CWE-200). Catches TODO / FIXME
  // comments containing secrets
  // and similar info-leak patterns.
  public boolean runCommentInfoLeak = true;
  // unchecked return value (CWE-252)
  public boolean runUncheckedReturn = true;
  // incorrect check of return value (CWE-253)
  public boolean runIncorrectReturn = true;
  // call to Thread.run() instead of start() (CWE-572)
  public boolean runThreadRun = true;
  // wrong operator for String comparison (CWE-597)
  public boolean runStringEquality = true;
  // catch of overly-generic exception type (CWE-396).
  // this flag only gates the
  // "do we translate TAC for the DFA-side check?" decision
  // (ScanRunner.java:1843). The actual CatchGenericException
  // rule is `dfa.cwe396-catch-generic-
  // exception`, a *plumb-style* AST check (OverlyBroadCatchCheck)
  // that runs unconditionally under the style family and is
  // filtered via `rules.enabled` / `rules.mode: whitelist` in
  // the YAML config. So this flag's default value has no
  // observable effect on the AST check.
  public boolean runCatchGeneric = true;
  // use of obsolete / deprecated Java API (CWE-477)
  public boolean runObsoleteFunction = true;
  // try-catch-finally DFA check (CWE-248/390/396/397)
  public boolean runTryCatchFinally = true;
  // off-by-one DFA check (CWE-193)
  public boolean runOffByOne = true;
  // cross-method DFA checkers that were
  // registered to plumb-dfa but never wired into ScanRunner.
  // Now wired in.
  public boolean runOrmLoadNullCheck = true;
  public boolean runOrmLostUpdate = true;
  public boolean runOrmUnnecessaryGet = true;
  public boolean runUnlockNotLocked = true;
  // ServletAtomicityDfa SingletonRaceDfa
  // suppressed because plumb-style has ServletAtomicityCheck
  // SingletonRaceCheck (concurrency/) covering the same
  // CWE-362 / CWE-366. Kept the flag for symmetry with the
  // other configurable checkers; default false to avoid
  // double-reporting with the plumb-style version.
  public boolean runServletAtomicity = false;
  public boolean runSingletonRace = false;
  // equals() called on null receiver / literal null arg
  // (CWE-595 / CWE-754)
  public boolean runEqualsCalledOnNull = true;
  // compare-with-self (CWE-570 / CWE-571)
  public boolean runCompareWithSelf = true;
  // XML external entity (CWE-611 / CVE-2014-3577)
  public boolean runXxe = true;
  // return in finally block (CWE-584 / CWE-459)
  public boolean runReturnFromFinally = true;
  // empty catch handler (CWE-390 / CWE-665)
  public boolean runEmptyCatchHandler = true;
  // iterator concurrent modification (CWE-820)
  public boolean runIteratorConcurrentModification = true;
  // collection self-reference (CWE-754)
  public boolean runCollectionSelfReference = true;
  // cross-function uncaught exception propagator (CWE-248)
  public boolean runUncaughtExceptionPropagator = true;
  // unchecked loop condition (CWE-606)
  public boolean runUncheckedLoopCondition = true;
  // resource exhaustion (CWE-400). The
  // ResourceExhaustionChecker also covers
  // Thread.sleep + for-loop bound, and the
  // plumb_rule_to_cwe.csv dual-maps it to CWE-606.
  public boolean runResourceExhaustion = true;
  // uncontrolled memory allocation (CWE-789)
  public boolean runUncontrolledMemAlloc = true;
  // numeric truncation (CWE-197)
  public boolean runNumericTruncation = true;
  // Legacy `org.plumb.dfa.checker.HardcodedCredentialCheck`
  // (a local-var MOVE pattern, CWE-798) — the existing
  // `runHardcodedCred` flag actually maps to the legacy
  // HardcodedCredentialCheck (NOT the field-level
  // FieldHardcodedPasswordCheck which has its own
  // `runFieldHardcodedPassword` flag). Reuse the
  // existing flag.
  // Category A DfaChecker for
  // `new BigDecimal(double)`. New flag, no legacy equivalent.
  public boolean runBigDecimalDoubleConstructor = true; // CWE-682
  // SpotBugs DM_DEFAULT_ENCODING mirror (CWE-176).
  // Old config used no explicit flag; default on.
  public boolean runDefaultEncoding = true; // CWE-176
  // loose checkers removed entirely. The
  // `runLooseRules` flag and the 9 Loose* checker files in
  // `plumb-dfa/.../checkers/` are gone. Coverage is held by
  // the strict `plumb.numeric.*` / `plumb.boundary.*` precise
  // rules new `plumb.ast.*` rules. See commit log for the
  // empirical numbers.
  public List<String> disabledRuleNames = new ArrayList<>();

  // audit: removed `methodLengthThreshold` —
  // was wired in ConfigLoader and the YAML but never read by any
  // style checker (plumb-style's MethodTooLongCheck has its own
  // hard-coded 80-line limit). Use `--rule pl-0249-method-too-long`
  // to disable the style check, or wait for a per-checker
  // threshold field in plumb-style.

  /**
   * R-PATCH: when true, rules.enabled acts as a strict whitelist -- every rule not listed is
   * force-disabled. Default false (preserves original Plumb behavior of force-enabling).
   */
  public boolean whitelistMode = false;

  public List<String> enabledRuleNames = new ArrayList<>();

  /** If non-empty, only files whose path matches one of these glob prefixes are scanned. */
  public List<String> includeGlobs = new ArrayList<>();

  public List<String> excludeGlobs = new ArrayList<>();

  // The --aggressiveness flag is gone.
  // Per-rule firing is controlled by the YAML's
  // `checks.<rule>.enabled` block (or the
  // --rule <id> CLI flag for one-off filtering). The
  // per-rule AI prompt is what drives the LLM pass,
  // so "aggressiveness" is now just a per-rule
  // decision, not a global knob.

  // When the user passes --rule <id> on the CLI,
  // only the listed rule ids fire (everything else is
  // off). This is independent of the YAML's
  // `checks.<rule>` flags. Multiple --rule accumulate.
  public List<String> cliRuleFilter = new ArrayList<>();

  // When the user passes --ai-dedup-stats, the
  // runner prints a verdict distribution + per-rule FP
  // ratio to stderr (so it doesn't pollute the NDJSON
  // on stdout). Useful to judge the AI dedup quality
  // on a fresh codebase without writing a downstream
  // analyser. CLI: --ai-dedup-stats. No YAML key (it's
  // a pure reporting toggle, not a behavior change).
  public boolean cliAiDedupStats = false;

  /**
   * LLM connection settings. Replaces the old {@code AIConfig} (deleted). Holds the provider, base
   * URL, API key, model, timeout -- just the HTTP plumbing, no behaviour flags.
   *
   * <p>Each checker's rule prompt lives in the checker itself ({@code DfaChecker.getAiPrompt} or
   * {@code AstChecker.getAiPrompt}). The runner looks up the right checker for each finding, calls
   * its {@code getAiPrompt}, and sends the prompt + finding context to the LLM.
   *
   * <p>{@code aiDedupRules} is a per-rule allowlist -- empty means "every rule that has a prompt".
   * {@code dropOnAiFp} controls whether findings tagged FP are dropped or kept with {@code
   * ai_verdict=FP}.
   */
  public org.plumb.ai.agent.LlmConnection aiConn = org.plumb.ai.agent.LlmConnection.disabled();

  /**
   * When true, the runner sends each finding to the LLM for FP/TP judgement. CLI: {@code
   * --ai-dedup}. YAML: {@code ai.dedup: true}.
   */
  public boolean aiDedup = false;

  /**
   * When true, FP-tagged findings are dropped from the final report. CLI / YAML: {@code
   * ai.dropOnAiFp}.
   */
  public boolean dropOnAiFp = false;

  /** Rule names whose findings should be sent for AI dedup. Empty = every rule with a prompt. */
  public java.util.List<String> aiDedupRules = new java.util.ArrayList<>();

  /** Max LLM round-trips in parallel. CLI / YAML: {@code ai.maxConcurrent}. */
  public int aiMaxConcurrent = 4;

  /** Temperature for the LLM. 0.0 = deterministic. */
  public double aiTemperature = 0.0;

  /**
   * Optional user-supplied directory of .md rule prompts (CLI {@code --rule-prompts-dir}). Files
   * here override each checker's inline {@code getAiPrompt} return value, keyed by {@code
   * <rule-name>.md}. Null = use the checker's built-in prompt.
   */
  public java.nio.file.Path cliRulePromptsDir;

  /**
   * AST rule files for the Kotlin DSL engine (CLI {@code --dsl-rules}).
   *
   * <p>Each file is a rule file in either the XML format the upstream {@code java-sca-ast-dsl}
   * project uses, or in YAML. The rules are compiled once and evaluated against every matching
   * JavaParser node.
   *
   * <p>This is the AST side only. Data-flow and taint rules are NOT configured here -- they stay in
   * {@code plumb-dfa}'s YAML files ({@code sink-families.yaml}, {@code checkers.yaml}).
   */
  public java.util.List<java.nio.file.Path> dslRuleFiles = new ArrayList<>();

  /**
   * When true, a DSL rule that fails to compile aborts the scan instead of being skipped.
   *
   * <p>Off by default: one broken rule file must not take a whole scan down. Turn it on in CI so a
   * broken rule is caught immediately.
   */
  public boolean dslStrict = false;

  /**
   * per-rule parameter overrides loaded from {@code checks.<ruleId>.params.<name>} in the YAML.
   * Outer key = rule id (the same string the AST checker uses as its {@code name()} or its
   * canonical rule id); inner key = parameter name (e.g. {@code "max"}, {@code "ignoreNumbers"});
   * value = {@link String} / {@link Integer} / {@link Boolean} matching the rule's {@link
   * org.plumb.rules.RuleParameter.Type}. Empty by default -- checker falls back to its declared
   * {@code RuleParameter.defaultValue()}.
   */
  public java.util.Map<String, java.util.Map<String, Object>> ruleParams =
      new java.util.HashMap<>();

  public enum OutputFormat {
    CONSOLE,
    NDJSON,
    SARIF
  }

  public void validate() {
    Objects.requireNonNull(inputs, "inputs");
    if (inputs.isEmpty()) {
      throw new IllegalArgumentException("At least one input file or directory required");
    }
    if (outputDir != null && !outputDir.toString().isEmpty()) {
      if (maxParallelism < 0) {
        throw new IllegalArgumentException("maxParallelism must be >= 0 (0 = auto)");
      }
    }
    if (cgStrategy == null || cgStrategy.isEmpty()) {
      throw new IllegalArgumentException(
          "cgStrategy must be non-empty (cha / rta / vta / 1cfa / stub / global /"
              + " cached:<name>:<path>)");
    }
    validateCgStrategy(cgStrategy);
  }

  /**
   * Reject obviously invalid call-graph strategy names so the user gets a clear error at
   * config-validation time rather than a confusing "Unknown strategy" deep inside the engine.
   */
  public static void validateCgStrategy(String name) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("cgStrategy must be non-empty");
    }
    String lower = name.toLowerCase();
    if (lower.startsWith("cached:")) {
      String[] parts = name.split(":", 3);
      if (parts.length != 3 || parts[1].isEmpty() || parts[2].isEmpty()) {
        throw new IllegalArgumentException(
            "cgStrategy cached form must be 'cached:<name>:<path>', got: " + name);
      }
      validateCgStrategy(parts[1]);
      return;
    }
    switch (lower) {
      case "cha":
      case "rta":
      case "vta":
      case "1cfa":
      case "stub":
      case "global":
        return;
      default:
        throw new IllegalArgumentException(
            "Unknown cgStrategy: '"
                + name
                + "' (expected cha | rta | vta | 1cfa | stub | global | cached:<name>:<path>)");
    }
  }
}

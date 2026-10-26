package org.plumb.main;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** Minimal YAML config loader using SnakeYAML. */
public final class ConfigLoader {

  private ConfigLoader() {}

  @SuppressWarnings("unchecked")
  public static ScanConfig load(Path yaml) throws IOException {
    try (InputStream in = Files.newInputStream(yaml)) {
      Map<String, Object> root = new Yaml().load(in);
      return fromMap(root == null ? Map.of() : root);
    }
  }

  @SuppressWarnings("unchecked")
  public static ScanConfig fromMap(Map<String, Object> root) {
    return fromMapImpl(root);
  }

  /**
   * Helper that returns the first non-null value among the candidates, supporting both snake_case
   * and camelCase yaml keys. Used by {@link #fromMapImpl} so legacy configs (snake_case) still take
   * effect.
   */
  private static Object firstNonNull(Object... values) {
    for (Object v : values) {
      if (v != null) return v;
    }
    return null;
  }

  /**
   * C-3 (audit 2026-10-08): print a clear stderr warning when a known-removed YAML key is present.
   * Helps the user understand why their config "doesn't do anything".
   */
  private static void warnIfPresent(Map<String, Object> root, String key, String msg) {
    warnIfPresent(root, key, msg, false);
  }

  private static void warnIfPresent(
      Map<String, Object> root, String key, String msg, boolean isMap) {
    Object v = root.get(key);
    if (v == null) return;
    if (isMap && !(v instanceof Map<?, ?>)) return;
    System.err.println(
        "[plumb] warning: '" + key + "' in YAML is " + "no longer supported; " + msg);
  }

  /**
   * Coerce a YAML value into a boolean. The legacy format was {@code checks.<rule>: true}; the new
   * format is {@code checks.<rule>: {enabled: true, aiDedup: true}}. This helper accepts both,
   * returning {@code defaultValue} when the key is absent or unparseable.
   */
  /**
   * coerce a YAML scalar into a String / Integer / Boolean. SnakeYAML parses {@code 80} as {@link
   * Integer}, {@code true} as {@link Boolean}, and quoted strings as {@link String}. The
   * StyleContext lookup handles the string → int / string → bool coercion at read time, so a
   * mis-typed user value (e.g. {@code max: "80"} with quotes) still works.
   */
  private static Object coerceParamValue(Object v) {
    if (v == null) return null;
    if (v instanceof String
        || v instanceof Boolean
        || v instanceof Integer
        || v instanceof Long
        || v instanceof Double) {
      return v;
    }
    // Fallback: stringify
    return String.valueOf(v);
  }

  private static boolean asBoolean(Object v, boolean defaultValue) {
    if (v == null) return defaultValue;
    if (v instanceof Boolean b) return b;
    if (v instanceof Map<?, ?> m) {
      Object e = m.get("enabled");
      if (e == null) return defaultValue;
      return Boolean.parseBoolean(String.valueOf(e));
    }
    if (v instanceof Number n) return n.intValue() != 0;
    return Boolean.parseBoolean(String.valueOf(v));
  }

  private static ScanConfig fromMapImpl(Map<String, Object> root) {
    // C-3 (audit 2026-10-08): warn when a known-removed top-level
    // key is present. We don't hard-fail (third-party scripts
    // copy-paste these from old examples), but the warning
    // helps the user understand why their config "doesn't
    // do anything". The `ai:` map is NOT warned at the
    // top level — only deprecated sub-keys inside it (see
    // below).
    warnIfPresent(
        root,
        "aggressiveness",
        "the aggressiveness flag is no longer honored; use "
            + "checks.<rule>.<enabled> in the YAML or --rule <id> on the CLI");
    ScanConfig cfg = new ScanConfig();
    Object inputs = root.get("inputs");
    if (inputs instanceof List<?> list) {
      for (Object o : list) {
        cfg.inputs.add(Path.of(String.valueOf(o)));
      }
    }
    Object single = root.get("input");
    if (single != null) cfg.inputs.add(Path.of(String.valueOf(single)));

    // external type-resolution inputs. These
    // three lists are consumed by JavaSourceParser's
    // TypeSolver only — never enter the TAC translation
    // pipeline or the SootUp bytecode path. They are
    // intentionally separate from `inputs` so the scan
    // target and the symbol-resolution target never
    // accidentally leak into each other. Both
    // snake_case (canonical) and camelCase (legacy alias)
    // keys are accepted.
    for (String[] aliases :
        new String[][] {
          {"source_roots", "sourceRoots"},
          {"module_roots", "moduleRoots"}
        }) {
      Object v = root.get(aliases[0]);
      if (v == null) v = root.get(aliases[1]);
      if (v instanceof List<?> list) {
        java.util.function.Consumer<Path> sink =
            aliases[0].equals("source_roots") ? cfg.sourceRoots::add : cfg.moduleRoots::add;
        for (Object o : list) sink.accept(Path.of(String.valueOf(o)));
      }
    }
    {
      Object v = root.get("classpath");
      if (v == null) v = root.get("classpathJars");
      if (v instanceof List<?> list) {
        for (Object o : list) cfg.classpathJars.add(Path.of(String.valueOf(o)));
      }
    }
    Object out = root.get("output");
    if (out != null) {
      if (out instanceof Map<?, ?> om) {
        Object path = om.get("path");
        if (path != null) cfg.outputPath = Path.of(String.valueOf(path));
        Object fmt = om.get("format");
        if (fmt != null)
          cfg.outputFormat = ScanConfig.OutputFormat.valueOf(String.valueOf(fmt).toUpperCase());
      } else {
        cfg.outputPath = Path.of(String.valueOf(out));
      }
    }
    Object outDir = root.get("outputDir");
    if (outDir == null) outDir = root.get("output-dir");
    if (outDir != null) cfg.outputDir = Path.of(String.valueOf(outDir));
    Object cacheMode = root.get("cacheMode");
    if (cacheMode == null) cacheMode = root.get("cache-mode");
    if (cacheMode != null) {
      cfg.cacheMode = ScanConfig.CacheMode.valueOf(String.valueOf(cacheMode).toUpperCase());
    }
    Object mp = root.get("maxParallelism");
    if (mp == null) mp = root.get("max-parallelism");
    if (mp != null) cfg.maxParallelism = Integer.parseInt(String.valueOf(mp));
    // Call-graph strategy. Both camelCase ("cgStrategy") and
    // snake_case ("cg-strategy") are accepted; camelCase wins when
    // both are present (compat convention).
    Object cgs = root.get("cgStrategy");
    if (cgs == null) cgs = root.get("cg-strategy");
    if (cgs != null) cfg.cgStrategy = String.valueOf(cgs);
    Object checks = root.get("checks");
    if (checks instanceof Map<?, ?> cm) {
      // Support both snake_case (legacy) and camelCase
      // (canonical) keys — older sweep configs and CLI flags
      // were written in snake_case. camelCase wins when both
      // are present.
      // C-2 (audit 2026-10-08): the two earlier
      // `firstNonNull(cm.get("taint"), cm.get("taint"))` lines
      // were typos — the second arg was the same as the first.
      // For single-word keys, snake_case is identical to the
      // camelCase spelling, so the typo is a no-op
      // (the lookup returns the same value either way).
      // Kept as a single `cm.get("taint")` to make the intent
      // explicit and to match the convention used below.
      Object t = cm.get("taint");
      if (t != null) cfg.runTaint = asBoolean(t, true);
      Object s = cm.get("style");
      if (s != null) cfg.runStyle = asBoolean(s, true);
      Object d = firstNonNull(cm.get("divByZero"), cm.get("div_by_zero"));
      if (d != null) cfg.runDivByZero = asBoolean(d, true);
      Object rl = firstNonNull(cm.get("resourceLeak"), cm.get("resource_leak"));
      if (rl != null) cfg.runResourceLeak = asBoolean(rl, true);
      Object nd = firstNonNull(cm.get("nullDeref"), cm.get("null_deref"));
      if (nd != null) cfg.runNullDeref = asBoolean(nd, true);
      Object hc = firstNonNull(cm.get("hardcodedCred"), cm.get("hardcoded_cred"));
      if (hc != null) cfg.runHardcodedCred = asBoolean(hc, true);
      Object io = firstNonNull(cm.get("intOverflow"), cm.get("int_overflow"));
      if (io != null) cfg.runIntOverflow = asBoolean(io, true);
      // CWE-191 integer underflow (Java DFA)
      Object iu = firstNonNull(cm.get("intUnderflow"), cm.get("int_underflow"));
      if (iu != null) cfg.runIntUnderflow = asBoolean(iu, true);
      Object ab = firstNonNull(cm.get("arrayBounds"), cm.get("array_bounds"));
      if (ab != null) cfg.runArrayBounds = asBoolean(ab, true);
      Object wc = firstNonNull(cm.get("weakCrypto"), cm.get("weak_crypto"));
      if (wc != null) cfg.runWeakCrypto = asBoolean(wc, true);
      Object ir = firstNonNull(cm.get("insecureRandom"), cm.get("insecure_random"));
      if (ir != null) cfg.runInsecureRandom = asBoolean(ir, true);
      Object ps = firstNonNull(cm.get("predictableSeed"), cm.get("predictable_seed"));
      if (ps != null) cfg.runPredictableSeed = asBoolean(ps, true);
      Object pk = firstNonNull(cm.get("predictableKey"), cm.get("predictable_key"));
      if (pk != null) cfg.runPredictableKey = asBoolean(pk, true);
      Object sak = firstNonNull(cm.get("staticApiKey"), cm.get("static_api_key"));
      if (sak != null) cfg.runStaticApiKey = asBoolean(sak, true);
      Object ic = firstNonNull(cm.get("insecureComms"), cm.get("insecure_comms"));
      if (ic != null) cfg.runInsecureComms = asBoolean(ic, true);
      Object cmc = firstNonNull(cm.get("corsMisconfig"), cm.get("cors_misconfig"));
      if (cmc != null) cfg.runCorsMisconfig = asBoolean(cmc, true);
      Object ick = firstNonNull(cm.get("insecureCookie"), cm.get("insecure_cookie"));
      if (ick != null) cfg.runInsecureCookie = asBoolean(ick, true);
      Object bs = firstNonNull(cm.get("badShift"), cm.get("bad_shift"));
      if (bs != null) cfg.runBadShift = asBoolean(bs, true);
      Object il = firstNonNull(cm.get("infiniteLoop"), cm.get("infinite_loop"));
      if (il != null) cfg.runInfiniteLoop = asBoolean(il, true);
      Object cc = firstNonNull(cm.get("constantCondition"), cm.get("constant_condition"));
      if (cc != null) cfg.runConstantCondition = asBoolean(cc, true);
      Object sdl = firstNonNull(cm.get("sensitiveDataLeak"), cm.get("sensitive_data_leak"));
      if (sdl != null) cfg.runSensitiveDataLeak = asBoolean(sdl, true);
      Object fhp =
          firstNonNull(cm.get("fieldHardcodedPassword"), cm.get("field_hardcoded_password"));
      if (fhp != null) cfg.runFieldHardcodedPassword = asBoolean(fhp, true);
      Object ma = firstNonNull(cm.get("missingAuthorization"), cm.get("missing_authorization"));
      if (ma != null) cfg.runMissingAuthorization = asBoolean(ma, true);
      Object miv =
          firstNonNull(cm.get("missingInputValidation"), cm.get("missing_input_validation"));
      if (miv != null) cfg.runMissingInputValidation = asBoolean(miv, true);
      Object ie = firstNonNull(cm.get("informationExposure"), cm.get("information_exposure"));
      if (ie != null) cfg.runInformationExposure = asBoolean(ie, true);
      Object ur = firstNonNull(cm.get("uncheckedReturn"), cm.get("unchecked_return"));
      if (ur != null) cfg.runUncheckedReturn = asBoolean(ur, true);
      Object irt = firstNonNull(cm.get("incorrectReturn"), cm.get("incorrect_return"));
      if (irt != null) cfg.runIncorrectReturn = asBoolean(irt, true);
      Object tr = firstNonNull(cm.get("threadRun"), cm.get("thread_run"));
      if (tr != null) cfg.runThreadRun = asBoolean(tr, true);
      Object seq = firstNonNull(cm.get("stringEquality"), cm.get("string_equality"));
      if (seq != null) cfg.runStringEquality = asBoolean(seq, true);
      Object cgen = firstNonNull(cm.get("catchGeneric"), cm.get("catch_generic"));
      if (cgen != null) cfg.runCatchGeneric = asBoolean(cgen, true);
      Object of = firstNonNull(cm.get("obsoleteFunction"), cm.get("obsolete_function"));
      if (of != null) cfg.runObsoleteFunction = asBoolean(of, true);
      // C-2 (audit 2026-10-08): the `assignmentInOperand`
      // key was historically mapped to
      // `bu-0400-assignment-in-operand` but the plumb-style
      // checker's class is `AssignmentInOperandCheck`. The
      // cfg flag was never wired (the rule is part of the
      // style group and is always on when `checks.style: true`).
      // Document the key as accepted-for-backward-compat
      // but ignored.
      Object aio = cm.get("assignmentInOperand");
      if (aio != null) {
        System.err.println(
            "[plumb] warning: checks.assignmentInOperand is "
                + "part of the style group; the value is ignored. Use "
                + "checks.style: "
                + cm.get("style")
                + " instead.");
      }
      // The DfaChecker ports
      Object bdc =
          firstNonNull(
              cm.get("bigDecimalDoubleConstructor"), cm.get("big_decimal_double_constructor"));
      if (bdc != null) cfg.runBigDecimalDoubleConstructor = asBoolean(bdc, true);
      Object de = firstNonNull(cm.get("defaultEncoding"), cm.get("default_encoding"));
      if (de != null) cfg.runDefaultEncoding = asBoolean(de, true);
      // loose checkers removed entirely.
      // The `loose` / `loose_rules` YAML keys are silently
      // ignored — old configs that still mention them just
      // get a warning at the [plumb] level, never crash.
      // 7 new unified-engine checkers
      Object tcf = firstNonNull(cm.get("tryCatchFinally"), cm.get("try_catch_finally"));
      if (tcf != null) cfg.runTryCatchFinally = asBoolean(tcf, true);
      Object obo = firstNonNull(cm.get("offByOne"), cm.get("off_by_one"));
      if (obo != null) cfg.runOffByOne = asBoolean(obo, true);
      Object econ = firstNonNull(cm.get("equalsCalledOnNull"), cm.get("equals_called_on_null"));
      if (econ != null) cfg.runEqualsCalledOnNull = asBoolean(econ, true);
      Object cws = firstNonNull(cm.get("compareWithSelf"), cm.get("compare_with_self"));
      if (cws != null) cfg.runCompareWithSelf = asBoolean(cws, true);
      Object xxe = firstNonNull(cm.get("xxe"), cm.get("xxe"));
      if (xxe != null) cfg.runXxe = asBoolean(xxe, true);
      Object rif = firstNonNull(cm.get("returnFromFinally"), cm.get("return_from_finally"));
      if (rif != null) cfg.runReturnFromFinally = asBoolean(rif, true);
      Object ech = firstNonNull(cm.get("emptyCatchHandler"), cm.get("empty_catch_handler"));
      if (ech != null) cfg.runEmptyCatchHandler = asBoolean(ech, true);
      // 2 more unified-engine checkers
      Object icm =
          firstNonNull(
              cm.get("iteratorConcurrentModification"), cm.get("iterator_concurrent_modification"));
      if (icm != null) cfg.runIteratorConcurrentModification = asBoolean(icm, true);
      Object csr =
          firstNonNull(cm.get("collectionSelfReference"), cm.get("collection_self_reference"));
      if (csr != null) cfg.runCollectionSelfReference = asBoolean(csr, true);
      // Cross-function uncaught exception
      Object uep =
          firstNonNull(
              cm.get("uncaughtExceptionPropagator"), cm.get("uncaught_exception_propagator"));
      if (uep != null) cfg.runUncaughtExceptionPropagator = asBoolean(uep, true);
      // Unchecked loop condition (CWE-606)
      Object ulc =
          firstNonNull(cm.get("uncheckedLoopCondition"), cm.get("unchecked_loop_condition"));
      if (ulc != null) cfg.runUncheckedLoopCondition = asBoolean(ulc, true);
      // Uncontrolled memory allocation (CWE-789)
      Object uma = firstNonNull(cm.get("uncontrolledMemAlloc"), cm.get("uncontrolled_mem_alloc"));
      if (uma != null) cfg.runUncontrolledMemAlloc = asBoolean(uma, true);
      // Numeric truncation (CWE-197)
      Object nt = firstNonNull(cm.get("numericTruncation"), cm.get("numeric_truncation"));
      if (nt != null) cfg.runNumericTruncation = asBoolean(nt, true);
      // audit: the 3 keys below were ScanConfig
      // fields and ScanRunner read them, but ConfigLoader didn't
      // parse the YAML — `checks.X: false` in user yaml was
      // silently ignored (always on). Wiring them up here.
      Object eml = firstNonNull(cm.get("errorMessageInfoLeak"), cm.get("error_message_info_leak"));
      if (eml != null) cfg.runErrorMessageInfoLeak = asBoolean(eml, true);
      Object cil = firstNonNull(cm.get("commentInfoLeak"), cm.get("comment_info_leak"));
      if (cil != null) cfg.runCommentInfoLeak = asBoolean(cil, true);
      Object rex = firstNonNull(cm.get("resourceExhaustion"), cm.get("resource_exhaustion"));
      if (rex != null) cfg.runResourceExhaustion = asBoolean(rex, true);
      // per-rule parameter overrides.
      // YAML shape:
      //   checks:
      //     line-length:
      //       params:
      //         max: 80
      //         ignoreComments: false
      // Each <rule> key in `checks:` is a rule id
      // (the same string the AST checker uses as
      // its `name()` or its canonical rule id).
      // Nested `params:` is a flat key-value map;
      // values are coerced to Integer / Boolean /
      // String based on the literal type.
      for (var e : cm.entrySet()) {
        Object v = e.getValue();
        if (!(v instanceof Map<?, ?> ruleBlock)) continue;
        Object params = ruleBlock.get("params");
        if (!(params instanceof Map<?, ?> pmap)) continue;
        String ruleKey = String.valueOf(e.getKey());
        cfg.ruleParams.computeIfAbsent(ruleKey, k -> new java.util.HashMap<>());
        for (var p : pmap.entrySet()) {
          String pname = String.valueOf(p.getKey());
          Object pval = coerceParamValue(p.getValue());
          if (pval != null) {
            cfg.ruleParams.get(ruleKey).put(pname, pval);
          }
        }
      }
    }
    Object rules = root.get("rules");
    if (rules instanceof Map<?, ?> rm) {
      Object disabled = rm.get("disabled");
      if (disabled instanceof List<?> list) {
        for (Object o : list) cfg.disabledRuleNames.add(String.valueOf(o));
      }
      Object enabled = rm.get("enabled");
      if (enabled instanceof List<?> list) {
        for (Object o : list) cfg.enabledRuleNames.add(String.valueOf(o));
      }
      Object whitelist = rm.get("whitelist");
      if (whitelist != null) cfg.whitelistMode = asBoolean(whitelist, false);
      Object mode = rm.get("mode");
      if (mode != null && "whitelist".equalsIgnoreCase(String.valueOf(mode))) {
        cfg.whitelistMode = true;
      }
    }
    // audit: `style.methodLengthThreshold` was
    // a dead field — ScanConfig had the int, ConfigLoader parsed
    // it, default-config.yaml set it, but no style checker ever
    // read it. The parser block is removed; old YAML files that
    // still mention the key get no warning and the value is
    // simply dropped (no error, no migration shim).
    // No `style.*` fields are parsed yet — reserved for future
    // per-checker thresholds when plumb-style grows them.
    Object inc = root.get("include");
    if (inc instanceof List<?> list) {
      for (Object o : list) cfg.includeGlobs.add(String.valueOf(o));
    }
    Object exc = root.get("exclude");
    if (exc instanceof List<?> list) {
      for (Object o : list) cfg.excludeGlobs.add(String.valueOf(o));
    }
    // AST rules for the Kotlin DSL engine. The `dsl:` block holds the
    // rule files and whether a broken rule aborts the scan:
    //
    //   dsl:
    //     rules: [rules/ast-rules.yaml]
    //     strict: true
    //
    // A bare `dsl: <path>` is also accepted, and means a single rule
    // file. Data-flow / taint rules are NOT configured here -- they
    // stay in plumb-dfa's YAML.
    Object dsl = root.get("dsl");
    if (dsl instanceof String s && !s.isBlank()) {
      cfg.dslRuleFiles.add(java.nio.file.Path.of(s));
    } else if (dsl instanceof List<?> list) {
      for (Object o : list) {
        String s = String.valueOf(o);
        if (!s.isBlank()) cfg.dslRuleFiles.add(java.nio.file.Path.of(s));
      }
    } else if (dsl instanceof Map<?, ?> dm) {
      Object dslRules = dm.get("rules");
      if (dslRules instanceof String s && !s.isBlank()) {
        cfg.dslRuleFiles.add(java.nio.file.Path.of(s));
      } else if (dslRules instanceof List<?> list) {
        for (Object o : list) {
          String s = String.valueOf(o);
          if (!s.isBlank()) cfg.dslRuleFiles.add(java.nio.file.Path.of(s));
        }
      }
      Object strict = dm.get("strict");
      if (strict instanceof Boolean b) cfg.dslStrict = b;
    }
    // Step 7 — AI dedup config. Accepts both `ai.*`
    // and `ai-dedup.*` (snake_case compat). Missing
    // key leaves the fields at their defaults.
    Object ai = root.get("ai");
    if (ai == null) ai = root.get("aiDedup");
    if (ai == null) ai = root.get("ai-dedup");
    if (ai instanceof Map<?, ?> am) {
      // C-3 (audit 2026-10-08): warn on per-key deprecated
      // fields inside the `ai:` map. We don't remove the
      // keys (third-party scripts copy them from older
      // examples), but the user gets a precise error.
      for (String deprecated :
          new String[] {"enabled", "scan", "contextLines", "rulePromptsDir", "sendAstContext"}) {
        if (am.containsKey(deprecated)) {
          String replacement =
              switch (deprecated) {
                case "enabled" -> "use --ai-dedup on the CLI";
                case "scan" -> "the bug scanner is not implemented yet (no replacement)";
                case "contextLines" ->
                    "the runner hard-codes a window of 3 lines around the finding";
                case "rulePromptsDir" -> "use --rule-prompts-dir on the CLI";
                case "sendAstContext" ->
                    "the field is unused; the runner always sends the AST when available";
                default -> "the field is unused";
              };
          System.err.println(
              "[plumb] warning: 'ai."
                  + deprecated
                  + "' in YAML is no longer supported; "
                  + replacement);
        }
      }
      // Step 7 — the YAML's `ai.*` block now
      // populates 4 small fields on ScanConfig
      // (aiDedup / dropOnAiFp / aiDedupRules /
      // aiMaxConcurrent) plus a single
      // LlmConnection record (provider / baseUrl /
      // apiKey / model / timeout). The old
      // `ai.scan` / `ai.rulePromptsDir` /
      // `ai.sendAstContext` keys are dropped.
      org.plumb.ai.agent.LlmConnection.Provider provider =
          org.plumb.ai.agent.LlmConnection.Provider.OPENAI_COMPATIBLE;
      String baseUrl = "https://api.openai.com";
      String apiKey = System.getenv("OPENAI_API_KEY"); // default from env
      String model = "gpt-4o-mini";
      String extraHeader = "";
      int timeoutSeconds = 30;
      for (var e : am.entrySet()) {
        String k = String.valueOf(e.getKey());
        Object v = e.getValue();
        switch (k) {
          case "provider" -> {
            // audit: OPENAI and
            // OPENAI_COMPATIBLE are wired through the
            // same code path in OpenAiCompatibleLlmProvider
            // (it only branches on ANTHROPIC). Treat the
            // bare "openai" alias as the more accurate
            // "openai_compatible" so the deprecation is
            // silent — old configs keep working.
            String raw = String.valueOf(v).trim().toLowerCase();
            if ("openai".equals(raw)) {
              provider = org.plumb.ai.agent.LlmConnection.Provider.OPENAI_COMPATIBLE;
            } else {
              try {
                provider =
                    org.plumb.ai.agent.LlmConnection.Provider.valueOf(
                        String.valueOf(v).toUpperCase());
              } catch (IllegalArgumentException iae) {
                throw new IllegalArgumentException(
                    "ai.provider must be one of "
                        + "openai | anthropic | openai_compatible, got: "
                        + v);
              }
            }
          }
          case "baseUrl" -> baseUrl = String.valueOf(v);
          case "apiKey" -> {
            // Allow "${ENV_VAR}" syntax to read from environment.
            String s = String.valueOf(v);
            if (s.startsWith("${") && s.endsWith("}")) {
              String envName = s.substring(2, s.length() - 1);
              apiKey = System.getenv(envName);
            } else {
              apiKey = s;
            }
          }
          case "model" -> model = String.valueOf(v);
          case "header" -> extraHeader = String.valueOf(v);
          case "timeoutSeconds" -> timeoutSeconds = Integer.parseInt(String.valueOf(v));
          case "maxConcurrent" -> cfg.aiMaxConcurrent = Integer.parseInt(String.valueOf(v));
          case "dedup" -> cfg.aiDedup = Boolean.parseBoolean(String.valueOf(v));
          case "dropOnAiFp" -> cfg.dropOnAiFp = Boolean.parseBoolean(String.valueOf(v));
          case "dedupRules" -> {
            if (v instanceof List<?> list) {
              for (Object o : list) cfg.aiDedupRules.add(String.valueOf(o));
            }
          }
          case "temperature" -> cfg.aiTemperature = Double.parseDouble(String.valueOf(v));
          default -> {
            /* ignore unknown */
          }
        }
      }
      cfg.aiConn =
          new org.plumb.ai.agent.LlmConnection(
              provider, baseUrl, apiKey, model, timeoutSeconds, extraHeader, null);
    }

    // Step 7 — the `aggressiveness` YAML key is
    // ignored (deleted). Per-rule firing is driven
    // by `checks.<rule>.enabled`; per-rule AI
    // dedup is driven by each checker's
    // getAiPrompt() return value (or by
    // ai.dedupRules in the YAML, which is
    // a per-rule allowlist of which rules
    // should be sent to the LLM).
    return cfg;
  }
}

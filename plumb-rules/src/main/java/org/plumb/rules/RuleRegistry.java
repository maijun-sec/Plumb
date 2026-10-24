package org.plumb.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory registry of {@link RuleDef}s.
 *
 * <h2>Two composition layers</h2>
 *
 * <ul>
 *   <li>{@link #withBuiltinDefaults()} ? register the 115 hard-coded entries for DFA checkers and
 *       the early-round taint / style / spotbugs-style rules. This is the only canonical list of
 *       pre-R-821 rules and the only code that mutates the {@link #byAlias} reverse index for
 *       cross-tool equivalence (PMD / SpotBugs / SonarQube Java / Checkstyle / Coverity).
 *   <li>{@link #registerFromChecker(Object)} ? read the {@code DEFAULT_RULE} static field (or its
 *       synthetic {@code rule()} derivation) from an {@link org.plumb.style.AstChecker} and add it
 *       to the registry. The {@code AstChecker} is the single source of truth for its own metadata;
 *       the registry is just an index. This is how R-821 unified the 600+ AST checker rules with
 *       the existing 115 DFA entries. Bulk operation {@link #registerFromCheckers(Iterable)} is
 *       what {@code ScanRunner.buildRegistry()} calls.
 * </ul>
 *
 * <h2>Alias resolution</h2>
 *
 * <p>Rules carry a list of aliases ? canonical rule ids and names of equivalent rules in other rule
 * sources (PMD, SpotBugs, SonarQube Java, Checkstyle, Coverity). When {@link #byAlias(String)} is
 * called with a legacy id or a foreign id, the registry returns the canonical Plumb {@code
 * RuleDef}. The {@link org.plumb.style.StyleContext#report} pipeline uses this to keep emitted
 * findings tagged with the canonical rule id even when the checker still carries a hardcoded legacy
 * {@code DEFAULT_RULE}.
 *
 * <h2>Parameter lookup</h2>
 *
 * <p>{@link #param(String, String, Class)} reads a configured parameter for the given rule,
 * returning the rule's declared default when no override is set. This is the runtime API that AST
 * checkers use to honour {@code checks.<ruleId>.params.<name>} entries in {@code checkers.yaml}.
 */
public final class RuleRegistry {

  public static final String TAINT_SOURCE_REQUEST_PARAM = "bu-0001-taint-request-param";
  public static final String TAINT_SOURCE_REQUEST_HEADER = "bu-0002-taint-request-header";
  public static final String TAINT_SOURCE_SYSTEM_IN = "bu-0003-taint-system-in";
  public static final String TAINT_SOURCE_FILE_READ = "bu-0004-taint-file-read";
  public static final String TAINT_SOURCE_NETWORK_RECV = "bu-0005-taint-network-recv";
  public static final String TAINT_SOURCE_CONSOLE_READ = "bu-0006-taint-console-read";
  public static final String TAINT_SOURCE_DB_RESULT = "bu-0007-taint-db-result";
  public static final String TAINT_SOURCE_SENSITIVE_DATA = "bu-0008-taint-source-sensitive-data";
  public static final String TAINT_SOURCE_SYSTEM_PROPERTY = "bu-0009-taint-source-system-property";
  public static final String TAINT_SOURCE_PROPERTIES_FILE = "bu-0010-taint-source-properties-file";
  public static final String TAINT_SINK_SQL_EXECUTE = "bu-0101-taint-sink-sql";
  public static final String TAINT_SINK_COMMAND_EXEC = "bu-0102-taint-sink-cmd";
  public static final String TAINT_SINK_PATH_TRAVERSAL = "bu-0103-taint-sink-path";
  public static final String TAINT_SINK_XSS_OUTPUT = "bu-0104-taint-sink-xss";
  public static final String TAINT_SINK_DESERIALIZE = "bu-0105-taint-sink-deserialize";
  public static final String TAINT_SINK_URL_OPEN = "bu-0106-taint-sink-url";
  public static final String TAINT_SINK_REFLECTION = "bu-0107-taint-sink-reflection";
  public static final String TAINT_SINK_LOG_INJECTION = "bu-0108-taint-sink-log";
  public static final String TAINT_SINK_XPATH = "bu-0109-taint-sink-xpath";
  public static final String TAINT_SINK_LDAP_INJECTION = "bu-0110-taint-sink-ldap";
  public static final String TAINT_SINK_XML_INJECTION = "bu-0111-taint-sink-xml";
  public static final String TAINT_SINK_HEADER_INJECTION = "bu-0112-taint-sink-header";
  public static final String TAINT_SINK_FORMAT_STRING = "bu-0113-taint-sink-format";
  public static final String TAINT_SINK_NOSQL_INJECTION = "bu-0114-taint-sink-nosql";
  public static final String TAINT_SINK_OGNL_INJECTION = "bu-0115-taint-sink-ognl";
  public static final String TAINT_SINK_REGEX_INJECTION = "bu-0116-taint-sink-regex";
  public static final String TAINT_SINK_OPEN_REDIRECT = "bu-0117-taint-sink-open-redirect";
  public static final String STYLE_EMPTY_CATCH = "bu-0201-style-empty-catch";
  public static final String STYLE_SYSTEM_OUT = "ast-style-system-out-println";
  public static final String STYLE_UNUSED_IMPORT = "bu-0203-style-unused-import";
  public static final String STYLE_CLASS_NAMING = "bu-0204-style-class-naming";
  public static final String STYLE_METHOD_NAMING = "bu-0205-style-method-naming";
  public static final String STYLE_CONSTANT_NAMING = "ast-naming-constants";
  public static final String STYLE_METHOD_TOO_LONG = "bu-0207-style-method-too-long";
  public static final String STYLE_CATCH_GENERIC = "ast-error-catch-generic-exception";
  public static final String STYLE_EMPTY_CONTROL_STATEMENT = "ast-style-empty-control-statement";
  public static final String STYLE_EMPTY_BLOCK = "bu-0210-style-empty-block";
  public static final String ASSIGNMENT_IN_OPERAND = "bu-0400-assignment-in-operand";
  public static final String AVOID_HARDCODED_IP = "bu-0406-avoid-hardcoded-ip";
  public static final String DEFAULT_ENCODING = "bu-0504-default-encoding";
  public static final String SUPERFLUOUS_INSTANCEOF = "bu-0505-superfluous-instanceof";
  public static final String BIGDECIMAL_DOUBLE_CTOR = "bu-0506-bigdecimal-double-ctor";
  public static final String RANDOM_USED_ONCE = "bu-0507-random-used-once";
  public static final String COLLECTION_IS_EMPTY = "bu-0604-collection-is-empty";
  public static final String EMPTY_STATEMENT = "bu-0605-empty-statement";
  public static final String CLASS_VAR_VISIBILITY = "ast-style-class-variable-visibility";
  public static final String BIG_DECIMAL_EQUALS = "bu-0607-big-decimal-equals";
  public static final String COV_MISSING_BREAK = "cov-0001-missing-break";
  public static final String SELF_ASSIGNMENT = "bu-0608-self-assignment";
  public static final String PARAM_HIDES_FIELD = "bu-0609-param-hides-field";
  public static final String COLLAPSIBLE_IF = "bu-0610-collapsible-if";
  public static final String IGNORED_RESULT = "bu-0611-ignored-result";
  public static final String AMBIGUOUS_PRECEDENCE = "bu-0612-ambiguous-precedence";
  public static final String ARRAY_EQUALITY = "bu-0613-array-equality";
  public static final String TEST_NO_ASSERTION = "bu-0614-test-no-assertion";
  public static final String EXTENDS_OBJECT = "bu-0701-extends-object";
  public static final String EMPTY_METHOD_IN_ABSTRACT = "bu-0702-empty-method-in-abstract";
  public static final String UNNECESSARY_RETURN = "bu-0703-unnecessary-return";
  public static final String LOCAL_VAR_COULD_BE_FINAL = "bu-0704-local-variable-could-be-final";
  public static final String COMMENT_DEFAULT_ACCESS = "bu-0705-comment-default-access";
  public static final String ARRAY_HASHCODE_TOSTRING = "bu-0801-array-hashcode-tostring";
  public static final String CASE_INSENSITIVE_COMPARISON = "bu-0802-case-insensitive-comparison";
  public static final String CATCH_NPE = "bu-0803-catch-nullpointer-exception";
  public static final String SYNC_STRING_BOXED = "bu-0804-synchronization-on-string-or-boxed";
  public static final String BOOLEAN_RETURNS_NULL = "bu-0805-boolean-method-returns-null";
  public static final String MUTABLE_STATIC_SHOULD_BE_FINAL =
      "bu-0901-mutable-static-should-be-final";
  public static final String LOCALE_DEPENDENT_CASE_CONVERSION =
      "bu-0902-locale-dependent-case-conversion";
  public static final String STRING_BUILDER_CONCATENATION_IN_LOOP =
      "bu-0903-string-builder-concatenation-in-loop";
  public static final String USELESS_OBJECT = "bu-0904-useless-object";
  public static final String INT_DIVISION_CAST_TO_DOUBLE = "bu-0905-int-division-cast-to-double";
  public static final String UNREACHABLE_CODE = "bu-1001-unreachable-code";
  public static final String UNUSED_LOCAL_VARIABLE = "bu-1101-unused-local-variable";
  public static final String UNUSED_FORMAL_PARAMETER = "bu-1102-unused-formal-parameter";
  public static final String AVOID_PRINT_STACK_TRACE = "bu-1103-avoid-print-stack-trace";
  public static final String STRING_EQUALITY_WITH_EQUALS = "bu-1104-string-equality-with-equals";
  public static final String EXPOSE_INTERNAL_REPRESENTATION =
      "bu-1105-expose-internal-representation";
  public static final String THROWABLE_NOT_THROWN = "bu-1106-throwable-not-thrown";
  public static final String HARDCODED_CREDENTIALS = "bu-1107-hardcoded-credentials";
  public static final String DEAD_STORE = "bu-1108-dead-store";

  public static final String PLUMB_STYLE_CALL_TO_THREAD_RUN = "ast-style-call-to-thread-run";
  public static final String PLUMB_STYLE_INFINITE_LOOP = "ast-style-infinite-loop";
  public static final String PLUMB_STYLE_NON_SERIALIZABLE_IN_SESSION =
      "ast-style-non-serializable-in-session";
  public static final String PLUMB_STYLE_EMPTY_SYNC_BLOCK = "ast-style-empty-sync-block";
  public static final String PLUMB_STYLE_DOUBLE_CHECKED_LOCKING =
      "ast-style-double-checked-locking";
  public static final String PLUMB_STYLE_COMMENT_INFO_LEAK = "ast-style-comment-info-leak";
  public static final String PLUMB_STYLE_ENV_VAR_INFO_EXPOSURE = "ast-style-env-var-info-exposure";
  public static final String PLUMB_STYLE_INFO_EXPOSURE_QUERYSTRING =
      "ast-style-info-exposure-querystring";
  public static final String PLUMB_STYLE_INSUFFICIENT_SESSION_EXPIRATION =
      "ast-style-insufficient-session-expiration";
  public static final String PLUMB_STYLE_MISSING_CRYPTO_STEP = "ast-style-missing-crypto-step";
  public static final String PLUMB_STYLE_RANDOM_IV_CBC = "ast-style-random-iv-cbc";
  public static final String PLUMB_STYLE_UNSALTED_ONE_WAY_HASH = "ast-style-unsalted-one-way-hash";
  public static final String PLUMB_STYLE_UNPROTECTED_CRED_TRANSPORT =
      "ast-style-unprotected-cred-transport";
  public static final String PLUMB_STYLE_CWE600_UNCAUGHT_EXCEPTION =
      "ast-style-cwe600-uncaught-exception";
  public static final String PLUMB_STYLE_POOR_CODE_QUALITY = "ast-style-poor-code-quality";
  public static final String PLUMB_STYLE_CWE580_CLONE_WITHOUT_SUPER =
      "ast-style-cwe580-clone-without-super";
  public static final String PLUMB_STYLE_CLASS_BASED_ISSUE_491 = "ast-style-class-based-issue-491";
  public static final String PLUMB_STYLE_NULL_DEREF_FROM_RETURN =
      "ast-style-null-deref-from-return";
  public static final String PLUMB_STYLE_SUSPICIOUS_COMMENT = "ast-style-suspicious-comment";

  private final Map<String, RuleDef> byUuid = new LinkedHashMap<>();
  private final Map<String, RuleDef> byName = new LinkedHashMap<>();
  private final Map<String, String> byAlias = new LinkedHashMap<>();

  public RuleRegistry() {}

  public RuleRegistry register(RuleDef def) {
    Objects.requireNonNull(def);
    if (byUuid.containsKey(def.ruleId().id())) {
      throw new IllegalStateException("Duplicate rule UUID: " + def.ruleId().id());
    }
    if (byName.containsKey(def.ruleId().name())) {
      throw new IllegalStateException("Duplicate rule name: " + def.ruleId().name());
    }
    byUuid.put(def.ruleId().id(), def);
    byName.put(def.ruleId().name(), def);
    for (String alias : def.aliases()) {
      claimAlias(alias, def.ruleId().id());
    }
    return this;
  }

  /**
   * Record that {@code alias} names the rule with id {@code ruleId}.
   *
   * <p>Two rules may legitimately declare the same alias: that is how a foreign tool id (e.g.
   * {@code SonarQube:java:S00108}) maps onto several equivalent Plumb rules. When that happens the
   * first registration wins, so the mapping does not depend on the order in which the two rules
   * happened to be registered.
   *
   * <p>An alias that is already a canonical id or a rule name of a <em>different</em> rule is not
   * recorded. The canonical id is the stronger claim: it is what findings are emitted with, and
   * letting an alias shadow it would make that rule unreachable by id.
   */
  private void claimAlias(String alias, String ruleId) {
    if (alias == null || alias.isEmpty()) return;
    if (byUuid.containsKey(alias) && !alias.equals(ruleId)) return;
    if (byName.containsKey(alias) && !alias.equals(ruleId)) return;
    byAlias.putIfAbsent(alias, ruleId);
  }

  /**
   * In-place replace for the same UUID/name. Used by per-CWE whitelist evaluation to flip a rule's
   * enabled flag without triggering the duplicate-rule exception raised by {@link
   * #register(RuleDef)}.
   */
  public RuleRegistry updateInPlace(RuleDef def) {
    Objects.requireNonNull(def);
    RuleDef previous = byUuid.get(def.ruleId().id());
    byUuid.put(def.ruleId().id(), def);
    byName.put(def.ruleId().name(), def);
    // Preserve aliases from the prior registration
    if (previous != null) {
      for (String alias : previous.aliases()) {
        claimAlias(alias, def.ruleId().id());
      }
    }
    for (String alias : def.aliases()) {
      claimAlias(alias, def.ruleId().id());
    }
    return this;
  }

  public Optional<RuleDef> byId(String id) {
    return Optional.ofNullable(byUuid.get(id));
  }

  public Optional<RuleDef> byName(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  /**
   * Look up a {@link RuleDef} by either its canonical id, a registered alias, or a legacy / foreign
   * rule id. Returns empty if neither matches.
   *
   * <h2>Resolution order</h2>
   *
   * <ol>
   *   <li>The canonical id ({@code byUuid}). A canonical id is the strongest claim on a string: it
   *       is the id a finding is actually emitted with, so it must win over any alias that happens
   *       to spell the same text.
   *   <li>The rule name ({@code byName}), i.e. the UPPER_SNAKE short name.
   *   <li>The alias index ({@code byAlias}).
   * </ol>
   *
   * <p>The alias index is last because aliases are a compatibility mechanism: they carry foreign
   * tool ids (PMD, SpotBugs, SonarQube) and historical Plumb spellings. A rule that declares
   * another rule's slug as an alias would otherwise hijack that slug, and the rule that genuinely
   * owns the slug would become unreachable by name. Resolving the canonical id first makes the
   * lookup deterministic regardless of registration order.
   */
  public Optional<RuleDef> byAlias(String alias) {
    if (alias == null) return Optional.empty();
    RuleDef direct = byUuid.get(alias);
    if (direct != null) return Optional.of(direct);
    RuleDef byNameHit = byName.get(alias);
    if (byNameHit != null) return Optional.of(byNameHit);
    String canonical = byAlias.get(alias);
    if (canonical == null) return Optional.empty();
    return Optional.ofNullable(byUuid.get(canonical));
  }

  /**
   * R-821 (2026-10-14): register a {@link RuleDef} sourced from an {@link
   * org.plumb.style.AstChecker} via that checker's own {@code rule()} method. This is the single
   * source of truth for AST checkers ? the {@link org.plumb.style.AstCheckerRegistry} calls this
   * for every discovered checker, and the resulting {@code RuleDef} entry replaces any duplicate
   * {@code withBuiltinDefaults()} registration (the by-UUID / by-name indices are updated in place,
   * so {@code byAlias()} lookups for the canonical id still work).
   *
   * <p>Returns silently when {@code checker.rule()} is null (no metadata available) or when the
   * resulting rule id is blank. The intent is for this method to be safe to call from any context,
   * including before a checker has been initialised.
   */
  public RuleRegistry registerFromChecker(Object checker) {
    if (checker == null) return this;
    try {
      java.lang.reflect.Method ruleMethod = checker.getClass().getMethod("rule");
      Object result = ruleMethod.invoke(checker);
      if (result instanceof RuleDef rd) {
        if (rd.ruleId() == null || rd.ruleId().id() == null || rd.ruleId().id().isEmpty()) {
          return this;
        }
        // Update in place so we don't crash on
        // duplicates (AstCheckerRegistry and
        // withBuiltinDefaults() both register
        // the same id).
        if (byUuid.containsKey(rd.ruleId().id()) || byName.containsKey(rd.ruleId().name())) {
          updateInPlace(rd);
        } else {
          register(rd);
        }
        claimCheckerSlug(checker, rd);
      }
    } catch (NoSuchMethodException ignored) {
      // Not an AstChecker (e.g. a DfaChecker) — skip.
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(
          "Failed to read rule() from " + checker.getClass().getName(), ex);
    }
    return this;
  }

  /**
   * Record the checker's own {@code name()} as an alias of its rule.
   *
   * <p>The checker slug is the key {@link org.plumb.style.StyleContext#setCurrentRule(String)} uses
   * to scope parameter overrides, and the key a user writes in {@code checks.<slug>} or passes to
   * {@code --rule}. If the slug is not resolvable to the checker's own rule, a {@code
   * checks.<slug>.params.*} override silently lands on the wrong rule (or nowhere), and the rule's
   * declared parameter defaults become unreachable through the slug.
   *
   * <p>Claiming the slug here makes that guarantee structural: a checker cannot forget to list its
   * own slug, because the registry derives it from the checker itself. The slug is only claimed
   * when it is not already a canonical id or rule name of a different rule, so a canonical id is
   * never shadowed.
   */
  private void claimCheckerSlug(Object checker, RuleDef rd) {
    try {
      java.lang.reflect.Method nameMethod = checker.getClass().getMethod("name");
      Object slug = nameMethod.invoke(checker);
      if (slug instanceof String s && !s.isEmpty()) {
        claimAlias(s, rd.ruleId().id());
      }
    } catch (NoSuchMethodException ignored) {
      // Not an AstChecker — nothing to claim.
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(
          "Failed to read name() from " + checker.getClass().getName(), ex);
    }
  }

  /**
   * R-821: bulk-register a collection of checkers via {@link #registerFromChecker(Object)}. The
   * {@link org.plumb.style.AstCheckerRegistry} calls this to mirror the 636 built-in {@code
   * AstChecker}s into the rule catalog.
   */
  public RuleRegistry registerFromCheckers(Iterable<? extends Object> checkers) {
    if (checkers == null) return this;
    for (Object c : checkers) {
      registerFromChecker(c);
    }
    return this;
  }

  /**
   * R-822 (2026-10-14): look up a configured parameter for a rule. The registry itself doesn't
   * store per-file overrides ? those live on the {@link org.plumb.style.StyleContext} (R761) ? so
   * this method returns the <em>declared default</em> from the rule's own {@link
   * RuleDef#parameters()} list, looked up by the parameter {@code name}.
   *
   * <p>This is the runtime API that AST checkers use to honour {@code
   * checks.<ruleId>.params.<name>} entries in {@code checkers.yaml}. Per-file overrides should be
   * resolved by the {@code StyleContext} (which has the {@code ruleParams} map) and the rule's
   * declared default returned here is the fallback when no override is set.
   *
   * <p>Returns {@code null} when the rule id is unknown or the parameter {@code name} is not
   * declared on the rule. The checker's own init-time lookup should be:
   *
   * <pre>
   *   Integer myParam = ctx.paramOverride("myParam", Integer.class);
   *   if (myParam == null) {
   *       RuleDef def = registry.byName("MY_RULE").orElse(null);
   *       myParam = (def == null) ? 10
   *                                 : def.parameters().stream()
   *                                       .filter(p -> p.name().equals("myParam"))
   *                                       .findFirst()
   *                                       .map(p -> p.intDefault())
   *                                       .orElse(10);
   *   }
   * </pre>
   *
   * For the common case, the {@link org.plumb.style.StyleContext#paramOverride} already
   * encapsulates the rule-scoped lookup; the registry's default-value fallback is a safety net for
   * tests and tools that don't carry a context.
   *
   * @param ruleId the rule id (canonical UUID, name, or alias ? same lookup as {@link
   *     #byAlias(String)})
   * @param name the parameter name
   * @param type the expected parameter type
   */
  public <T> T param(String ruleId, String name, Class<T> type) {
    if (ruleId == null || name == null) return null;
    // R-822 + R-823: accept the checker-facing slug
    // (e.g. "empty-block") in addition to the
    // canonical RuleId.name (e.g. "EMPTY_BLOCK")
    // and the UUID. Convert kebab-case to upper
    // snake case on the way through so the legacy
    // 382 checkers whose {@code name()} returns
    // kebab-case but whose {@code DEFAULT_RULE}
    // uses PMD/SpotBugs-style UPPER_SNAKE still
    // resolve cleanly.
    RuleDef def = byAlias(ruleId).orElseGet(() -> byName(ruleId).orElse(null));
    if (def == null) {
      String slugUpper =
          ruleId.replace('-', '_').replace('.', '_').toUpperCase(java.util.Locale.ROOT);
      if (!slugUpper.equals(ruleId)) {
        def = byName(slugUpper).orElse(null);
      }
    }
    if (def == null) return null;
    for (RuleParameter p : def.parameters()) {
      if (!p.name().equals(name)) continue;
      String v = p.defaultValue();
      if (v == null) return null;
      if (type == Integer.class) {
        try {
          return type.cast(Integer.valueOf(v));
        } catch (NumberFormatException ex) {
          return null;
        }
      }
      if (type == Boolean.class) {
        return type.cast(Boolean.valueOf(v));
      }
      if (type == String.class) {
        return type.cast(v);
      }
      return null;
    }
    return null;
  }

  public boolean hasAlias(String alias) {
    return byAlias.containsKey(alias) || byUuid.containsKey(alias);
  }

  public List<RuleDef> all() {
    return Collections.unmodifiableList(new ArrayList<>(byUuid.values()));
  }

  public List<RuleDef> enabled() {
    return all().stream().filter(RuleDef::enabled).toList();
  }

  /**
   * R-834: re-walk {@link #byUuid} and flip every {@link RuleDef#enabled()} to {@code true}
   * regardless of its declared default. Used by {@link #withBuiltinDefaults(boolean)} after the AST
   * checker mirror pass to make sure the disable-by-default flag a AST checker sets on its {@code
   * DEFAULT_RULE} doesn't shadow the test mode override.
   */
  private void forceAllEnabledIf(boolean forceAllEnabled) {
    if (!forceAllEnabled) return;
    for (RuleDef def : byUuid.values()) {
      if (!def.enabled()) {
        updateInPlace(def.withEnabled(true));
      }
    }
  }

  /**
   * R-834: reflection-load every {@code AstChecker} on the classpath via {@code plumb-style}'s
   * {@code AstCheckerRegistry}, then {@link #registerFromChecker(Object) mirror} each checker's
   * {@code DEFAULT_RULE} into this registry. Best-effort: a {@code ClassNotFoundException} means
   * {@code plumb-style} is not on the classpath (e.g. an isolated {@code plumb-ai} test); the
   * mirror step silently no-ops in that case. A {@code ReflectiveOperationException} of any other
   * kind is logged and swallowed ? smoke tests should not depend on this side-effect for their
   * correctness.
   *
   * <p>{@code plumb-rules} keeps a clean compile-time edge to {@code plumb-style} (the AST checker
   * types live in plumb-style), so this lookup goes through {@link Class#forName(String)} plus
   * reflective {@code AstCheckerRegistry.builtins()} + {@code all()} rather than {@code import
   * org.plumb.style.AstCheckerRegistry}.
   */
  private void tryMirrorAstCheckers() {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) cl = RuleRegistry.class.getClassLoader();
    Class<?> registryCls;
    try {
      registryCls = Class.forName("org.plumb.style.AstCheckerRegistry", true, cl);
    } catch (ClassNotFoundException ex) {
      // plumb-style not on classpath (e.g. isolated plumb-ai
      // test). Smoke-test mode just falls back to the
      // built-in DFA list with no AST mirror.
      return;
    }
    try {
      Object reg = registryCls.getDeclaredConstructor().newInstance();
      registryCls.getMethod("builtins").invoke(reg);
      Object all = registryCls.getMethod("all").invoke(reg);
      if (!(all instanceof Iterable<?> iter)) return;
      registerFromCheckers(iter);
    } catch (ReflectiveOperationException ex) {
      // Best-effort: don't crash the registry init on a
      // reflective hiccup in a sibling module.
      java.util.logging.Logger.getLogger(RuleRegistry.class.getName())
          .fine("tryMirrorAstCheckers failed: " + ex.getMessage());
    }
  }

  public RuleRegistry withBuiltinDefaults() {
    return withBuiltinDefaults(false);
  }

  /**
   * R-834: {@code withBuiltinDefaults} overload that takes a {@code forceAllEnabled} flag. When
   * {@code true}, every registered rule is force-enabled, overriding the disable-by-default flag
   * that some STYLE / NAMING checkers set in their {@code DEFAULT_RULE}. Production never calls
   * this; smoke tests that want to exercise a rule's detection logic regardless of the disable flag
   * use it.
   *
   * <p>Side effect when {@code forceAllEnabled} is true: the method also walks the {@code
   * plumb-style} {@code AstCheckerRegistry} via reflection (to avoid pulling {@code plumb-style}
   * into {@code plumb-rules}'s compile graph) and registers every AST checker's {@code
   * DEFAULT_RULE} via {@link #registerFromChecker(Object)}. The subsequent {@link
   * #forceAllEnabledIf(boolean)} pass flips every disabled-by-default checker back to {@code
   * enabled = true}. Without this mirroring, a smoke test that built the registry with {@code
   * withBuiltinDefaults(true)} but did not separately call {@code registerFromChecker} for each AST
   * checker it exercises would see {@link StyleContext#report(int, int, RuleDef, String,
   * RuleDef.Severity, String)} drop every finding (because {@code rules.byId(id)} returns empty and
   * the bypass short-circuits on the empty optional).
   */
  public RuleRegistry withBuiltinDefaults(boolean forceAllEnabled) {
    // Idempotency: if a previous call already populated
    // the registry, skip the heavy builtin + mirror
    // blocks. This lets R825-style double-invocation
    // tests like
    //   `new RuleRegistry()
    //      .withBuiltinDefaults(true)
    //      .withBuiltinDefaults(true)`
    // work without re-registering the 115+593 rules
    // and tripping the Duplicate-rule-UUID guard.
    boolean alreadyPopulated = !byUuid.isEmpty();
    // The AST checker mirror is intentionally NOT done
    // here at the top — builtin rule ids like
    // `ast-style-system-out-println` collide with the
    // `SystemOutCheck.defaultRule()` id, and the
    // reverse order (mirror first, then builtin
    // `register`) would throw Duplicate rule UUID on
    // every smoke test. The mirror runs AFTER the
    // builtin block plus the addAlias block (see
    // below, just before `forceAllEnabledIf`).
    if (alreadyPopulated) {
      // Re-apply the forceAllEnabled flag to any
      // rules that may have been registered after
      // the first call (e.g. via
      // registerFromChecker) without bypassing the
      // guard. Cheap O(byUuid) pass.
      forceAllEnabledIf(forceAllEnabled);
      return this;
    }

    // ─── Taint sources ─────────────────────────────────
    register(
        def(
            TAINT_SOURCE_REQUEST_PARAM,
            "TAINT_SOURCE_REQUEST_PARAM",
            "Tainted from HTTP request parameter",
            RuleDef.Severity.INFO,
            RuleDef.Type.TAINT,
            List.of("CWE-20"),
            "org.plumb.dfa.taint.RequestParamSource"));
    register(
        def(
            TAINT_SOURCE_REQUEST_HEADER,
            "TAINT_SOURCE_REQUEST_HEADER",
            "Tainted from HTTP request header",
            RuleDef.Severity.INFO,
            RuleDef.Type.TAINT,
            List.of("CWE-20"),
            "org.plumb.dfa.taint.RequestHeaderSource"));
    register(
        def(
            TAINT_SOURCE_SYSTEM_IN,
            "TAINT_SOURCE_SYSTEM_IN",
            "Tainted from System.* input stream",
            RuleDef.Severity.INFO,
            RuleDef.Type.TAINT,
            List.of("CWE-20"),
            "org.plumb.dfa.taint.SystemInSource"));
    register(
        def(
            TAINT_SOURCE_FILE_READ,
            "TAINT_SOURCE_FILE_READ",
            "Tainted from file read",
            RuleDef.Severity.INFO,
            RuleDef.Type.TAINT,
            List.of("CWE-20"),
            "org.plumb.dfa.taint.FileReadSource"));
    register(
        def(
            TAINT_SOURCE_NETWORK_RECV,
            "TAINT_SOURCE_NETWORK_RECV",
            "Tainted from network receive",
            RuleDef.Severity.INFO,
            RuleDef.Type.TAINT,
            List.of("CWE-20"),
            "org.plumb.dfa.taint.NetworkRecvSource"));
    register(
        def(
            TAINT_SOURCE_CONSOLE_READ,
            "TAINT_SOURCE_CONSOLE_READ",
            "Tainted from console read",
            RuleDef.Severity.INFO,
            RuleDef.Type.TAINT,
            List.of("CWE-20"),
            "org.plumb.dfa.taint.ConsoleReadSource"));
    register(
        def(
            TAINT_SOURCE_DB_RESULT,
            "TAINT_SOURCE_DB_RESULT",
            "Tainted from database read",
            RuleDef.Severity.INFO,
            RuleDef.Type.TAINT,
            List.of("CWE-20"),
            "org.plumb.dfa.taint.DbResultSource"));
    register(
        def(
            TAINT_SOURCE_SENSITIVE_DATA,
            "TAINT_SOURCE_SENSITIVE_DATA",
            "Tainted from sensitive data source (password / session / secret / token)",
            RuleDef.Severity.INFO,
            RuleDef.Type.TAINT,
            List.of("CWE-532"),
            "org.plumb.dfa.taint.SensitiveSource"));
    register(
        def(
            TAINT_SOURCE_SYSTEM_PROPERTY,
            "TAINT_SOURCE_SYSTEM_PROPERTY",
            "Tainted from JVM system property or environment variable",
            RuleDef.Severity.INFO,
            RuleDef.Type.TAINT,
            List.of("CWE-20"),
            "org.plumb.dfa.taint.SystemPropertySource"));
    register(
        def(
            TAINT_SOURCE_PROPERTIES_FILE,
            "TAINT_SOURCE_PROPERTIES_FILE",
            "Tainted from Java .properties file (Properties.getProperty / .get)",
            RuleDef.Severity.INFO,
            RuleDef.Type.TAINT,
            List.of("CWE-20"),
            "org.plumb.dfa.taint.PropertiesFileSource"));

    // ─── Taint sinks ────────────────────────────────────
    register(
        def(
            TAINT_SINK_SQL_EXECUTE,
            "TAINT_SINK_SQL_INJECTION",
            "SQL statement built from tainted data",
            RuleDef.Severity.HIGH,
            RuleDef.Type.TAINT,
            List.of("CWE-89"),
            "org.plumb.dfa.taint.SqlSink"));
    register(
        def(
            TAINT_SINK_COMMAND_EXEC,
            "TAINT_SINK_COMMAND_INJECTION",
            "OS command built from tainted data",
            RuleDef.Severity.CRITICAL,
            RuleDef.Type.TAINT,
            List.of("CWE-78", "CWE-77"),
            "org.plumb.dfa.taint.CommandSink"));
    register(
        def(
            TAINT_SINK_PATH_TRAVERSAL,
            "TAINT_SINK_PATH_TRAVERSAL",
            "Filesystem path built from tainted data",
            RuleDef.Severity.HIGH,
            RuleDef.Type.TAINT,
            List.of("CWE-22"),
            "org.plumb.dfa.taint.PathSink"));
    register(
        def(
            TAINT_SINK_XSS_OUTPUT,
            "TAINT_SINK_XSS",
            "Web response output built from tainted data",
            RuleDef.Severity.HIGH,
            RuleDef.Type.TAINT,
            List.of("CWE-79"),
            "org.plumb.dfa.taint.XssSink"));
    register(
        def(
            TAINT_SINK_DESERIALIZE,
            "TAINT_SINK_UNSAFE_DESERIALIZE",
            "Deserialization of tainted data",
            RuleDef.Severity.CRITICAL,
            RuleDef.Type.TAINT,
            List.of("CWE-502"),
            "org.plumb.dfa.taint.DeserializeSink"));
    register(
        def(
            TAINT_SINK_URL_OPEN,
            "TAINT_SINK_SSRF",
            "URL connection opened from tainted data (SSRF)",
            RuleDef.Severity.HIGH,
            RuleDef.Type.TAINT,
            List.of("CWE-918"),
            "org.plumb.dfa.taint.UrlOpenSink"));
    register(
        def(
            TAINT_SINK_REFLECTION,
            "TAINT_SINK_REFLECTION",
            "Reflection target built from tainted data",
            RuleDef.Severity.HIGH,
            RuleDef.Type.TAINT,
            List.of("CWE-470"),
            "org.plumb.dfa.taint.ReflectionSink"));
    register(
        def(
            TAINT_SINK_LOG_INJECTION,
            "TAINT_SINK_LOG_INJECTION",
            "Log entry built from tainted data",
            RuleDef.Severity.LOW,
            RuleDef.Type.TAINT,
            List.of("CWE-117"),
            "org.plumb.dfa.taint.LogInjectionSink"));
    register(
        def(
            TAINT_SINK_XPATH,
            "TAINT_SINK_XPATH_INJECTION",
            "XPath expression built from tainted data",
            RuleDef.Severity.HIGH,
            RuleDef.Type.TAINT,
            List.of("CWE-643"),
            "org.plumb.dfa.taint.XPathSink"));
    register(
        def(
            TAINT_SINK_LDAP_INJECTION,
            "TAINT_SINK_LDAP_INJECTION",
            "LDAP search/lookup built from tainted data",
            RuleDef.Severity.HIGH,
            RuleDef.Type.TAINT,
            List.of("CWE-90"),
            "org.plumb.dfa.taint.LdapSink"));
    register(
        def(
            TAINT_SINK_XML_INJECTION,
            "TAINT_SINK_XML_INJECTION",
            "XML transform / parse built from tainted data",
            RuleDef.Severity.HIGH,
            RuleDef.Type.TAINT,
            List.of("CWE-91"),
            "org.plumb.dfa.taint.XmlSink"));
    register(
        def(
            TAINT_SINK_HEADER_INJECTION,
            "TAINT_SINK_HEADER_INJECTION",
            "HTTP response header set from tainted data",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.TAINT,
            List.of("CWE-93"),
            "org.plumb.dfa.taint.HeaderSink"));
    register(
        def(
            TAINT_SINK_FORMAT_STRING,
            "TAINT_SINK_FORMAT_STRING",
            "Format string built from tainted data",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.TAINT,
            List.of("CWE-134"),
            "org.plumb.dfa.taint.FormatStringSink"));
    register(
        def(
            TAINT_SINK_NOSQL_INJECTION,
            "TAINT_SINK_NOSQL_INJECTION",
            "NoSQL query built from tainted data",
            RuleDef.Severity.HIGH,
            RuleDef.Type.TAINT,
            List.of("CWE-943"),
            "org.plumb.dfa.taint.NoSqlSink"));
    register(
        def(
            TAINT_SINK_OGNL_INJECTION,
            "TAINT_SINK_OGNL_INJECTION",
            "Expression language / OGNL evaluated from tainted data",
            RuleDef.Severity.CRITICAL,
            RuleDef.Type.TAINT,
            List.of("CWE-94", "CWE-95"),
            "org.plumb.dfa.taint.OgnlSink"));
    register(
        def(
            TAINT_SINK_REGEX_INJECTION,
            "TAINT_SINK_REGEX_INJECTION",
            "Regular expression compiled from tainted data (ReDoS / bypass)",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.TAINT,
            List.of("CWE-1333"),
            "org.plumb.dfa.taint.RegexSink"));
    register(
        def(
            TAINT_SINK_OPEN_REDIRECT,
            "TAINT_SINK_OPEN_REDIRECT",
            "Redirect URL built from tainted data",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.TAINT,
            List.of("CWE-601"),
            "org.plumb.dfa.taint.OpenRedirectSink"));

    // ─── Style (early round) ────────────────────────────
    register(
        def(
            STYLE_EMPTY_CATCH,
            "STYLE_EMPTY_CATCH",
            "Empty catch block swallows exceptions",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.EmptyCatchCheck"));
    register(
        def(
            STYLE_SYSTEM_OUT,
            "STYLE_SYSTEM_OUT",
            "Avoid System.out / System.err in production code",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.SystemOutCheck"));
    register(
        def(
            STYLE_UNUSED_IMPORT,
            "STYLE_UNUSED_IMPORT",
            "Unused import statement",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.UnusedImportCheck"));
    register(
        def(
            STYLE_CLASS_NAMING,
            "STYLE_CLASS_NAMING",
            "Type name should follow PascalCase",
            RuleDef.Severity.LOW,
            RuleDef.Type.NAMING,
            List.of(),
            "org.plumb.style.ClassNamingCheck"));
    register(
        def(
            STYLE_METHOD_NAMING,
            "STYLE_METHOD_NAMING",
            "Method name should follow camelCase",
            RuleDef.Severity.LOW,
            RuleDef.Type.NAMING,
            List.of(),
            "org.plumb.style.MethodNamingCheck"));
    register(
        def(
            STYLE_CONSTANT_NAMING,
            "STYLE_CONSTANT_NAMING",
            "Static final field should be UPPER_SNAKE_CASE",
            RuleDef.Severity.LOW,
            RuleDef.Type.NAMING,
            List.of(),
            "org.plumb.style.ConstantNamingCheck"));
    register(
        def(
            STYLE_METHOD_TOO_LONG,
            "STYLE_METHOD_TOO_LONG",
            "Method body exceeds configured length threshold",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.MethodLengthCheck"));
    register(
        def(
            STYLE_CATCH_GENERIC,
            "STYLE_CATCH_GENERIC",
            "Catching generic Exception or Throwable",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.CatchGenericCheck"));
    register(
        def(
            STYLE_EMPTY_CONTROL_STATEMENT,
            "STYLE_EMPTY_CONTROL_STATEMENT",
            "control statement with empty body",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.EmptyControlStatementCheck"));
    register(
        def(
            STYLE_EMPTY_BLOCK,
            "STYLE_EMPTY_BLOCK",
            "control block with no statements",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.EmptyBlockCheck"));

    // ─── Correctness / spotbugs-style port (early round) ─
    register(
        def(
            ASSIGNMENT_IN_OPERAND,
            "ASSIGNMENT_IN_OPERAND_LEGACY",
            "avoid assignments in operands",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.STYLE,
            List.of("CWE-481"),
            "org.plumb.dfa.checker.AssignmentInOperandCheck"));
    register(
        def(
            AVOID_HARDCODED_IP,
            "AVOID_HARDCODED_IP",
            "hard-coded IP address",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.STYLE,
            List.of("CWE-547"),
            "org.plumb.dfa.checker.HardcodedIPCheck"));
    register(
        def(
            DEFAULT_ENCODING,
            "DEFAULT_ENCODING",
            "reliance on default platform encoding",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.CORRECTNESS,
            List.of("CWE-176"),
            "org.plumb.dfa.checker.DefaultEncodingCheck"));
    register(
        def(
            SUPERFLUOUS_INSTANCEOF,
            "SUPERFLUOUS_INSTANCEOF",
            "unnecessary instanceof check",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of("CWE-704"),
            "org.plumb.style.SuperfluousInstanceofCheck"));
    register(
        def(
            BIGDECIMAL_DOUBLE_CTOR,
            "BIGDECIMAL_DOUBLE_CTOR",
            "new BigDecimal(double) is precision-lossy",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.CORRECTNESS,
            List.of("CWE-682"),
            "org.plumb.dfa.checker.BigDecimalDoubleConstructorCheck"));
    register(
        def(
            RANDOM_USED_ONCE,
            "RANDOM_USED_ONCE",
            "new Random() used only once",
            RuleDef.Severity.LOW,
            RuleDef.Type.PERFORMANCE,
            List.of(),
            "org.plumb.style.RandomUsedOnceCheck"));
    register(
        def(
            COLLECTION_IS_EMPTY,
            "COLLECTION_IS_EMPTY_LEGACY",
            "prefer Collection.isEmpty() over size() == 0",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of("CWE-1006"),
            "org.plumb.style.CollectionIsEmptyCheck"));
    register(
        def(
            EMPTY_STATEMENT,
            "EMPTY_STATEMENT_LEGACY",
            "empty statement (naked semicolon)",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.EmptyStatementUsageCheck"));
    register(
        def(
            CLASS_VAR_VISIBILITY,
            "CLASS_VAR_VISIBILITY",
            "public static field should be final or non-public",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of("CWE-500"),
            "org.plumb.style.ClassVariableVisibilityCheck"));
    register(
        def(
            BIG_DECIMAL_EQUALS,
            "BIG_DECIMAL_EQUALS",
            "BigDecimal.equals is value-and-scale, use compareTo",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.CORRECTNESS,
            List.of("CWE-682"),
            "org.plumb.style.BigDecimalEqualsCheck"));
    register(
        def(
            SELF_ASSIGNMENT,
            "SELF_ASSIGNMENT_LEGACY",
            "variable should not be self-assigned (no-op, likely copy-paste bug)",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.CORRECTNESS,
            List.of("CWE-682"),
            "org.plumb.style.SelfAssignmentCheck"));
    register(
        def(
            PARAM_HIDES_FIELD,
            "PARAM_HIDES_FIELD",
            "method parameter shadows a class field",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.CORRECTNESS,
            List.of("CWE-1006"),
            "org.plumb.style.ParameterHidesFieldCheck"));
    register(
        def(
            COLLAPSIBLE_IF,
            "COLLAPSIBLE_IF_LEGACY",
            "nested if statements can be collapsed into one with &&",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.CollapsibleIfCheck"));
    register(
        def(
            IGNORED_RESULT,
            "IGNORED_RESULT",
            "result of comparison-style method call is ignored",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.CORRECTNESS,
            List.of("CWE-252"),
            "org.plumb.style.ResultOfMethodCallIgnoredCheck"));
    register(
        def(
            AMBIGUOUS_PRECEDENCE,
            "AMBIGUOUS_PRECEDENCE_LEGACY",
            "ambiguous operator precedence, add explicit parentheses",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.CORRECTNESS,
            List.of("CWE-783"),
            "org.plumb.style.AmbiguousOperatorPrecedenceCheck"));
    register(
        def(
            ARRAY_EQUALITY,
            "ARRAY_EQUALITY",
            "arrays should not be compared with == (use Arrays.equals instead)",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.CORRECTNESS,
            List.of("CWE-697"),
            "org.plumb.style.ArrayEqualityCheck"));
    register(
        def(
            TEST_NO_ASSERTION,
            "TEST_NO_ASSERTION",
            "test method should include at least one assertion",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.CORRECTNESS,
            List.of(),
            "org.plumb.style.TestWithoutAssertionCheck"));

    // ─── Mega-round: tool prefix dropped, plumb-style + plumb-dfa ports ─
    register(
        def(
            EMPTY_METHOD_IN_ABSTRACT,
            "EMPTY_METHOD_IN_ABSTRACT",
            "Empty method body in abstract class should be marked abstract",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.EmptyMethodInAbstractCheck"));
    register(
        def(
            UNNECESSARY_RETURN,
            "UNNECESSARY_RETURN_LEGACY",
            "Void method ends with `return;` (no value, no effect)",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.UnnecessaryReturnCheck"));
    register(
        def(
            LOCAL_VAR_COULD_BE_FINAL,
            "LOCAL_VAR_COULD_BE_FINAL",
            "Local variable assigned only once can be final",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.LocalVariableCouldBeFinalCheck"));
    // CWE-15 (External Control of System or Configuration
    // Setting) taint-flow sink. The setCatalog / setSchema /
    // setTypeMap / JNDI bind patterns are the canonical
    // sinks in the Juliet CWE-15 family. The TaintCatalog
    // has the patterns; this entry registers the rule_id
    // so the TaintAnalysis can resolve it.
    register(
        def(
            "bu-0708-taint-sink-ext-control",
            "TAINT_SINK_EXT_CONTROL",
            "External control of system or configuration setting (CWE-15: setCatalog / setSchema /"
                + " JNDI bind with tainted input)",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.SECURITY,
            List.of("CWE-15"),
            "org.plumb.dfa.taint.TaintAnalysis"));
    register(
        def(
            ARRAY_HASHCODE_TOSTRING,
            "ARRAY_HASHCODE_TOSTRING",
            "arr.toString() / arr.hashCode() should use Arrays.toString / Arrays.hashCode",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.CORRECTNESS,
            List.of("CWE-477"),
            "org.plumb.style.ArrayHashCodeAndToStringCheck"));
    register(
        def(
            CASE_INSENSITIVE_COMPARISON,
            "CASE_INSENSITIVE_COMPARISON",
            "Use equalsIgnoreCase() instead of toLowerCase/toUpperCase before equals",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.CORRECTNESS,
            List.of(),
            "org.plumb.style.CaseInsensitiveComparisonCheck"));
    register(
        def(
            CATCH_NPE,
            "CATCH_NPE_LEGACY",
            "Avoid catching NullPointerException (likely swallows a real bug)",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.BAD_PRACTICE,
            List.of("CWE-396"),
            "org.plumb.style.CatchNpeCheck"));
    register(
        def(
            SYNC_STRING_BOXED,
            "SYNC_STRING_BOXED",
            "Synchronize on interned String / boxed primitive is unsafe (use a private Object"
                + " lock)",
            RuleDef.Severity.HIGH,
            RuleDef.Type.MULTITHREADING,
            List.of("CWE-820"),
            "org.plumb.style.SynchronizationOnStringOrBoxedCheck"));
    register(
        def(
            USELESS_OBJECT,
            "USELESS_OBJECT_LEGACY",
            "Object created and discarded without use",
            RuleDef.Severity.LOW,
            RuleDef.Type.PERFORMANCE,
            List.of(),
            "org.plumb.style.UselessObjectCheck"));
    register(
        def(
            INT_DIVISION_CAST_TO_DOUBLE,
            "INT_DIVISION_CAST_TO_DOUBLE",
            "Integer division cast to double (precision loss; cast one operand instead)",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.IntDivisionCastToDoubleCheck"));
    // 7 plumb-style + 1 plumb-dfa
    register(
        def(
            UNUSED_LOCAL_VARIABLE,
            "UNUSED_LOCAL_VARIABLE",
            "Local variable declared but never read",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.UnusedLocalVariableCheck"));
    register(
        def(
            UNUSED_FORMAL_PARAMETER,
            "UNUSED_FORMAL_PARAMETER_LEGACY",
            "Method parameter declared but never used",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.UnusedFormalParameterCheck"));
    register(
        def(
            AVOID_PRINT_STACK_TRACE,
            "AVOID_PRINT_STACK_TRACE",
            "Avoid Throwable.printStackTrace(); use a logger or re-throw",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.AvoidPrintStackTraceCheck"));
    register(
        def(
            DEAD_STORE,
            "DEAD_STORE",
            "Value assigned to variable is overwritten before being read",
            RuleDef.Severity.LOW,
            RuleDef.Type.CORRECTNESS,
            List.of("CWE-563"),
            "org.plumb.dfa.checker.DeadStoreCheck"));

    // ─── DFA / security / webapp checkers ───────────────
    register(
        def(
            "bu-0401-weak-crypto",
            "WEAK_CRYPTO",
            "Use of cryptographically weak algorithm (MD5/SHA-1/DES/RC4/ECB)",
            RuleDef.Severity.HIGH,
            RuleDef.Type.SECURITY,
            List.of("CWE-327", "CWE-328"),
            "org.plumb.dfa.WeakCryptoCheck"));
    register(
        def(
            "bu-0402-insecure-random",
            "INSECURE_RANDOM",
            "Use of java.util.Random in security-sensitive context",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.SECURITY,
            List.of("CWE-330"),
            "org.plumb.dfa.InsecureRandomCheck"));
    register(
        def(
            "bu-0403-predictable-seed",
            "PREDICTABLE_RANDOM_SEED",
            "Random generator seeded with a compile-time constant",
            RuleDef.Severity.HIGH,
            RuleDef.Type.SECURITY,
            List.of("CWE-332", "CWE-336"),
            "org.plumb.dfa.PredictableRandomSeedCheck"));
    register(
        def(
            "bu-0404-predictable-key-password",
            "PREDICTABLE_KEY_PASSWORD",
            "Key material / password is a compile-time literal",
            RuleDef.Severity.CRITICAL,
            RuleDef.Type.SECURITY,
            List.of("CWE-259", "CWE-798"),
            "org.plumb.dfa.PredictableKeyPasswordCheck"));
    register(
        def(
            "bu-0405-static-api-key",
            "STATIC_API_KEY",
            "Static final field holding a literal API key / secret",
            RuleDef.Severity.CRITICAL,
            RuleDef.Type.SECURITY,
            List.of("CWE-798", "CWE-259"),
            "org.plumb.dfa.StaticApiKeyCheck"));
    register(
        def(
            "bu-0601-bad-shift",
            "BAD_SHIFT",
            "Suspicious shift count: negative or exceeds operand bit width",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.CORRECTNESS,
            List.of("CWE-758"),
            "org.plumb.dfa.BadShiftCheck"));
    register(
        def(
            "bu-0602-infinite-loop",
            "INFINITE_LOOP",
            "Loop with a constant-true condition and no visible exit",
            RuleDef.Severity.LOW,
            RuleDef.Type.CORRECTNESS,
            List.of("CWE-835"),
            "org.plumb.dfa.InfiniteLoopCheck"));
    register(
        def(
            "bu-0603-constant-condition",
            "CONSTANT_CONDITION",
            "Branch condition is a compile-time boolean constant",
            RuleDef.Severity.LOW,
            RuleDef.Type.CORRECTNESS,
            List.of("CWE-570", "CWE-571"),
            "org.plumb.dfa.ConstantConditionCheck"));
    register(
        def(
            "bu-0701-sensitive-data-leak",
            "SENSITIVE_DATA_LEAK",
            "Sensitive data (password / session / secret) flows to log / throw / file",
            RuleDef.Severity.CRITICAL,
            RuleDef.Type.SECURITY,
            List.of("CWE-532"),
            "org.plumb.dfa.SensitiveDataLeakCheck"));
    register(
        def(
            "bu-0801-field-hardcoded-password",
            "FIELD_HARDCODED_PASSWORD",
            "Field with credential-like name is assigned a literal password / secret",
            RuleDef.Severity.CRITICAL,
            RuleDef.Type.SECURITY,
            List.of("CWE-798", "CWE-259"),
            "org.plumb.dfa.FieldHardcodedPasswordCheck"));
    register(
        def(
            "bu-0901-missing-authorization",
            "MISSING_AUTHORIZATION",
            "Web endpoint method lacks an authorization annotation (@PreAuthorize / @RolesAllowed /"
                + " @Secured)",
            RuleDef.Severity.HIGH,
            RuleDef.Type.SECURITY,
            List.of("CWE-862", "CWE-285"),
            "org.plumb.dfa.MissingAuthorizationCheck"));
    register(
        def(
            "bu-1001-missing-input-validation",
            "MISSING_INPUT_VALIDATION",
            "Web endpoint accepts user input without Bean Validation (@Valid / @Validated)",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.SECURITY,
            List.of("CWE-20"),
            "org.plumb.dfa.MissingInputValidationCheck"));
    register(
        def(
            "bu-1101-information-exposure",
            "INFORMATION_EXPOSURE",
            "Web endpoint returns a user-declared class (consider a sanitized DTO)",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.SECURITY,
            List.of("CWE-200"),
            "org.plumb.dfa.InformationExposureCheck"));
    register(
        def(
            "bu-0501-insecure-comms",
            "INSECURE_COMMUNICATION",
            "Unencrypted (http/ftp) URL used for sensitive endpoint",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.SECURITY,
            List.of("CWE-319"),
            "org.plumb.dfa.InsecureCommunicationCheck"));
    register(
        def(
            "bu-0502-cors-misconfig",
            "CORS_MISCONFIGURATION",
            "CORS configuration allows any origin ('*')",
            RuleDef.Severity.HIGH,
            RuleDef.Type.SECURITY,
            List.of("CWE-942"),
            "org.plumb.dfa.CorsMisconfigurationCheck"));
    register(
        def(
            "bu-0503-insecure-cookie",
            "INSECURE_COOKIE",
            "Cookie constructed without Secure / HttpOnly flags",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.SECURITY,
            List.of("CWE-614", "CWE-1004"),
            "org.plumb.dfa.InsecureCookieCheck"));

    // ─── A3: cross-tool equivalence aliases ──────────────
    // The values in this table are tool-side identifiers by
    // design (they're foreign keys, not part of the Plumb
    // rule's own identity). The Plumb rule id, name, and
    // description are all tool-prefix-free. After
    // withBuiltinDefaults() finishes, walk every rule and
    // populate the byAlias reverse index from each rule's
    // own .aliases() — see register().
    addAlias(STYLE_EMPTY_CATCH, "PMD:EmptyCatchBlock", "SonarQube:java:S1163");
    addAlias(STYLE_SYSTEM_OUT, "SonarQube:java:S106", "PMD:SystemPrintln");
    addAlias(STYLE_UNUSED_IMPORT, "PMD:UnusedImport", "SonarQube:java:S00108");
    addAlias(STYLE_CLASS_NAMING, "PMD:ClassNamingConventions", "SonarQube:java:S00101");
    addAlias(SELF_ASSIGNMENT, "SonarQube:java:S1656");
    addAlias(PARAM_HIDES_FIELD, "SonarQube:java:S1226");
    addAlias(COLLAPSIBLE_IF, "SonarQube:java:S1066", "PMD:CollapsibleIfStatements");
    addAlias(IGNORED_RESULT, "SonarQube:java:S899");
    addAlias(AMBIGUOUS_PRECEDENCE, "SonarQube:java:S888");
    addAlias(ARRAY_EQUALITY, "SonarQube:java:S3012");
    addAlias(TEST_NO_ASSERTION, "SonarQube:java:S2699");
    addAlias(EMPTY_METHOD_IN_ABSTRACT, "PMD:EmptyMethodInAbstractClassShouldBeAbstract");
    addAlias(UNNECESSARY_RETURN, "PMD:UnnecessaryReturn");
    addAlias(LOCAL_VAR_COULD_BE_FINAL, "PMD:LocalVariableCouldBeFinal");
    addAlias(ARRAY_HASHCODE_TOSTRING, "SonarQube:java:S2116");
    addAlias(CASE_INSENSITIVE_COMPARISON, "SonarQube:java:S1157");
    addAlias(CATCH_NPE, "SonarQube:java:S1696");
    addAlias(SYNC_STRING_BOXED, "SonarQube:java:S1860");
    addAlias(USELESS_OBJECT, "SpotBugs:UC_USELESS_OBJECT");
    addAlias(INT_DIVISION_CAST_TO_DOUBLE, "SpotBugs:ICAST_IDIV_CAST_TO_DOUBLE");
    addAlias(UNUSED_LOCAL_VARIABLE, "PMD:UnusedLocalVariable");
    addAlias(UNUSED_FORMAL_PARAMETER, "PMD:UnusedFormalParameter");
    addAlias(AVOID_PRINT_STACK_TRACE, "PMD:AvoidPrintStackTrace");
    addAlias(DEAD_STORE, "Coverity:DEAD_STORE", "SpotBugs:DLS_DEAD_LOCAL_STORE");
    addAlias(STYLE_METHOD_NAMING, "PMD:MethodNamingConventions", "SonarQube:java:S00100");
    addAlias(STYLE_CONSTANT_NAMING, "PMD:ConstantNamingConventions");
    addAlias(STYLE_METHOD_TOO_LONG, "PMD:ExcessiveMethodLength", "SonarQube:java:S00108");
    addAlias(STYLE_CATCH_GENERIC, "PMD:TooGenericExceptionCaught", "SonarQube:java:S00122");
    addAlias(STYLE_EMPTY_CONTROL_STATEMENT, "PMD:EmptyControlStatement");
    addAlias(STYLE_EMPTY_BLOCK, "PMD:EmptyBlock", "SonarQube:java:S00108");
    addAlias(ASSIGNMENT_IN_OPERAND, "PMD:AssignmentInOperand", "SonarQube:java:S00121");
    addAlias("bu-0401-weak-crypto", "SpotBugs:CIPHER_INTEGRITY", "SonarQube:java:S5547");
    addAlias(
        "bu-0402-insecure-random", "SpotBugs:RANDOM_INSECURE_USAGE", "PMD:UseConcurrentHashMap");
    addAlias(AVOID_HARDCODED_IP, "PMD:AvoidUsingHardCodedIP");
    addAlias(DEFAULT_ENCODING, "SpotBugs:DM_DEFAULT_ENCODING", "SonarQube:java:S1943");
    addAlias(SUPERFLUOUS_INSTANCEOF, "SpotBugs:SIO_SUPERFLUOUS_INSTANCEOF");
    addAlias("bu-0901-missing-authorization", "SonarQube:java:S00115");
    addAlias("bu-1001-missing-input-validation", "SonarQube:java:S00114");
    addAlias("bu-1101-information-exposure", "SonarQube:java:S00108");

    // ─── R788 ──────────────────────────────────────────────────────
    // Canonical plumb.style.* rules for R787a-j source-reading
    // checkers. The `CweNNN` (R787a-j) and the dfa-side
    // `dfa.<slug>` ids are registered as aliases so the
    // Finding.ruleId() emitted to NDJSON is the canonical
    // `plumb.style.<slug>` form, with back-compat aliases for
    // any consumer that still uses the legacy ids.

    // R-834: only when the caller asked for forceAllEnabled,
    // pull in the AST checker mirror so any checker with
    // DEFAULT_RULE.enabled=false is still registered
    // (and then flipped back to enabled=true below).
    // The mirror runs here — AFTER the built-in
    // register/addAlias blocks — so the built-in
    // `register(...)` calls won't trip the
    // "Duplicate rule UUID" guard.
    if (forceAllEnabled) {
      tryMirrorAstCheckers();
    }

    // R-834: optionally force-enable every registered rule.
    // The first pass walks the DFA builtin rules and the
    // AST mirror set (added by tryMirrorAstCheckers when
    // forceAllEnabled is true) so any checker whose
    // DEFAULT_RULE carries enabled=false still emits in
    // smoke-test mode.
    forceAllEnabledIf(forceAllEnabled);

    return this;
  }

  private void addAlias(String ruleId, String... aliases) {
    RuleDef orig = byUuid.get(ruleId);
    if (orig == null) return;
    RuleDef updated = orig.withAliases(List.of(aliases));
    byUuid.put(ruleId, updated);
    byName.put(orig.ruleId().name(), updated);
    for (String alias : aliases) {
      byAlias.put(alias, ruleId);
    }
  }

  private static RuleDef def(
      String id,
      String name,
      String description,
      RuleDef.Severity severity,
      RuleDef.Type type,
      List<String> cwe,
      String checkerClass) {
    return new RuleDef(new RuleId(id, name), description, severity, type, cwe, checkerClass, true);
  }

  /**
   * R788: overload that takes enabled flag + aliases, used by the canonical plumb.style.*
   * registrations. Backed by RuleDef's 8-arg constructor.
   */
  private static RuleDef def(
      String id,
      String name,
      String description,
      RuleDef.Severity severity,
      RuleDef.Type type,
      List<String> cwe,
      String checkerClass,
      boolean enabled,
      List<String> aliases) {
    return new RuleDef(
        new RuleId(id, name),
        description,
        severity,
        type,
        cwe,
        checkerClass,
        enabled,
        aliases == null ? List.of() : aliases);
  }
}

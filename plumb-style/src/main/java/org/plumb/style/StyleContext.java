package org.plumb.style;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-file style-check context. The visitor walks a {@link
 * com.github.javaparser.ast.CompilationUnit} and any {@link AstChecker} implementation can read the
 * source string and report findings by calling {@link #report(int, int, RuleDef, String,
 * RuleDef.Severity, String)}.
 *
 * <p><b>Aggressiveness knob.</b> The {@link #withAggressiveness(String)} setter + {@link
 * #isAtLeast(String)} gate mirror Coverity's {@code --aggressiveness-level} command-line option.
 * AST checkers call {@code ctx.isAtLeast("high")} to enable "high only" branches (broader patterns,
 * more cross-function assumptions).
 */
public final class StyleContext {

  private static final Logger LOG = LoggerFactory.getLogger(StyleContext.class);

  private final ParsedSource source;
  private final RuleRegistry rules;
  private final List<Finding> findings = new ArrayList<>();

  /**
   * a set of rule ids that bypass the {@code enabled} gate in {@link #report}. Production code
   * never populates this set; smoke tests that exercise a rule's detection logic can call {@link
   * #forceEnable(String...)} so the disable-by-default flag (a Juliet-driven policy decision)
   * doesn't blank the finding list.
   */
  private final java.util.Set<String> forcedRuleIds = new java.util.HashSet<>();

  /**
   * parameter overrides keyed by {@code <ruleId>.<paramName>}. The full map is built once at
   * construction (from the ScanConfig's {@code checks.<ruleId>.params.<name>} block) and never
   * mutated. Lookups go through {@link #paramOverride(String, Class)} after {@link
   * #setCurrentRule(String)} has been called by the runner, so the per-check per-file lookup is a
   * single {@link HashMap#get} with no thread-local magic.
   *
   * <p>Why the composite key instead of a per-ruleId map-of-maps? The existing 32 {@link
   * org.plumb.style.AstChecker} implementations call {@code ctx.paramOverride("max",
   * Integer.class)} with the bare param name; scoping by ruleId would require changing every one of
   * them. The composite key is invisible to the call site.
   */
  private final Map<String, Object> paramOverrides = new HashMap<>();

  /**
   * the rule id (or short name) that the current {@code init()} call belongs to. Set by the runner
   * before each checker's {@code init()}. Lookups in {@link #paramOverride(String, Class)} prefix
   * the param name with this value to scope per-checker.
   */
  private String currentRule = "";

  /**
   * Coverity-style aggressiveness knob: "low" / "medium" / "high". Defaults to "medium" (the
   * Coverity baseline).
   */
  private String aggressiveness = "medium";

  /**
   * cross-file static-method cache for nullness / null-return analysis. Keyed by {@code
   * <FQCN>#<methodName>} (e.g. {@code "testcases.CWE690_...Helper#getStringGood"}), value is the
   * {@link com.github.javaparser.ast.body.MethodDeclaration} of the static method. Populated by
   * {@code plumb-main}'s {@code ScanRunner} from every source-root / module-root supplied via
   * {@code --source-root} / {@code --module-root}, and passed in via the new {@link
   * #StyleContext(ParsedSource, RuleRegistry, Map, Map)} constructor.
   *
   * <p>Used by {@link org.plumb.style.style.NullDerefFromReturnCheck} to determine whether a {@code
   * data = Helper.foo()} assignment's RHS is definitely-non-null (because {@code Helper.foo()}
   * returns a literal or a freshly constructed object). The cache is per-scan immutable so AST
   * walkers can read concurrently.
   */
  private final Map<String, com.github.javaparser.ast.body.MethodDeclaration> staticMethodByFqcn =
      new HashMap<>();

  /**
   * M2: per-file cache of top-level method ranges, sorted by start line. Built lazily on the first
   * {@link #findEnclosingMethod} call and reused for every subsequent call. Before this cache,
   * every finding triggered a full {@code unit.walk(MethodDeclaration)} ? for a file that produces
   * N findings, that's N redundant traversals.
   *
   * <p>{@link #methodRanges} holds tuples {@code [startLine, endLine, signatureHash]}; {@link
   * #signatureByHash} maps a signature string hash to the formatted {@code name(p1,...):ret}
   * signature. Two distinct methods with identical name+params+return type are extremely rare; if
   * they collide we pick the first one registered consistently.
   */
  private java.util.List<int[]> methodRanges = null;

  private java.util.Map<Integer, String> signatureByHash = null;
  private com.github.javaparser.ast.CompilationUnit methodRangesBuiltFor = null;

  public StyleContext(ParsedSource source, RuleRegistry rules) {
    this(source, rules, Map.of(), Map.of());
  }

  /**
   * construct a context with a pre-populated per-rule parameter map. The map's outer key is the
   * rule id (or short name) ? the same string the runner will pass to {@link
   * #setCurrentRule(String)} before each {@code init()}. The inner key is the parameter name (e.g.
   * {@code "max"}, {@code "ignoreNumbers"}). Values are {@link String}, {@link Integer}, or {@link
   * Boolean} (see {@link RuleParameter.Type}). A null {@code ruleParams} is treated as empty.
   */
  public StyleContext(
      ParsedSource source, RuleRegistry rules, Map<String, Map<String, Object>> ruleParams) {
    this(source, rules, ruleParams, Map.of());
  }

  /**
   * full constructor with the cross-file static-method cache. {@code staticMethods} keys are {@code
   * <FQCN>#<methodName>}, values are the parsed {@link
   * com.github.javaparser.ast.body.MethodDeclaration}. A null map is treated as empty.
   */
  public StyleContext(
      ParsedSource source,
      RuleRegistry rules,
      Map<String, Map<String, Object>> ruleParams,
      Map<String, com.github.javaparser.ast.body.MethodDeclaration> staticMethods) {
    this.source = Objects.requireNonNull(source);
    this.rules = Objects.requireNonNull(rules);
    if (ruleParams != null) {
      for (var e : ruleParams.entrySet()) {
        String ruleId = e.getKey();
        Map<String, Object> params = e.getValue();
        if (params == null) continue;
        for (var p : params.entrySet()) {
          if (p.getValue() != null) {
            paramOverrides.put(compositeKey(ruleId, p.getKey()), p.getValue());
          }
        }
      }
    }
    if (staticMethods != null) {
      staticMethodByFqcn.putAll(staticMethods);
    }
  }

  /**
   * bypass the {@code enabled} gate for the given rule ids. Production code never calls this; the
   * prod scan runner uses {@code RuleRegistry}'s whitelist / blacklist instead. Smoke tests that
   * want to exercise a disable-by-default rule's detection logic can call this on the {@link
   * StyleContext} they build for the test.
   */
  public void forceEnable(String... ruleIds) {
    for (String id : ruleIds) {
      if (id != null) forcedRuleIds.add(id);
    }
  }

  private static String compositeKey(String ruleId, String paramName) {
    return ruleId + "." + paramName;
  }

  public ParsedSource source() {
    return source;
  }

  public RuleRegistry rules() {
    return rules;
  }

  public List<Finding> findings() {
    return findings;
  }

  /**
   * look up a static method by FQCN + method name. The key is {@code <FQCN>#<methodName>}, e.g.
   * {@code
   * "testcases.CWE690_NULL_Deref_From_Return.CWE690_NULL_Deref_From_Return__Class_Helper#getStringGood"}.
   * Returns null if the cache has no entry for that key. The returned declaration is the same
   * {@link com.github.javaparser.ast.body.MethodDeclaration} the runner extracted at scan-prep
   * time; treat it as read-only.
   */
  public com.github.javaparser.ast.body.MethodDeclaration lookupStaticMethod(
      String fqcn, String methodName) {
    if (fqcn == null || methodName == null) return null;
    return staticMethodByFqcn.get(fqcn + "#" + methodName);
  }

  /** Set the per-run aggressiveness. */
  public StyleContext withAggressiveness(String level) {
    this.aggressiveness = normaliseAggressiveness(level);
    return this;
  }

  /** Current aggressiveness. */
  public String aggressiveness() {
    return aggressiveness;
  }

  /**
   * True if the configured aggressiveness is at or above the requested level. {@code
   * ctx.isAtLeast("high")} — "high-only" gate.
   */
  public boolean isAtLeast(String level) {
    if (level == null) return true;
    return rank(this.aggressiveness) >= rank(normaliseAggressiveness(level));
  }

  private static int rank(String level) {
    if ("high".equals(level)) return 2;
    if ("medium".equals(level)) return 1;
    return 0;
  }

  private static String normaliseAggressiveness(String v) {
    if (v == null) return "medium";
    String s = v.toLowerCase(Locale.ROOT).trim();
    if ("low".equals(s) || "medium".equals(s) || ("high".equals(s))) {
      return s;
    }
    return "medium";
  }

  /**
   * Set a parameter override for this scan. Checkers read parameter values via {@link
   * #paramOverride(String, Class)}. The key is a string like the value is the parameter's type.
   */
  public StyleContext withParamOverride(String name, Object value) {
    paramOverrides.put(name, value);
    return this;
  }

  /**
   * set the current rule id so subsequent {@link #paramOverride(String, Class)} lookups prefix the
   * name with the rule id. Called by the runner before each checker's {@code init()}. Pass the same
   * string that was used as the outer key of the {@code ruleParams} map at construction time
   * (usually the checker's {@code name()} or its canonical rule id).
   */
  public StyleContext setCurrentRule(String ruleId) {
    this.currentRule = ruleId == null ? "" : ruleId;
    return this;
  }

  /** Current rule id (may be empty if no {@code setCurrentRule} call yet). */
  public String currentRule() {
    return currentRule;
  }

  /**
   * Look up a parameter override for the given name, scoped by the current rule (set via {@link
   * #setCurrentRule(String)}). Returns null if no override is set; the caller should fall back to
   * the rule's declared default.
   */
  public <T> T paramOverride(String name, Class<T> type) {
    if (currentRule.isEmpty()) {
      // Backwards compat: if no rule scope is set, fall
      // back to the bare name. This lets very old
      // AstChecker call sites (pre-) keep working.
      return legacyLookup(name, type);
    }
    Object v = paramOverrides.get(compositeKey(currentRule, name));
    if (v != null) {
      return coerce(v, type);
    }
    // no per-file override for this
    // rule. Fall back to the rule's declared default
    // from its {@link RuleDef#parameters()} list. This
    // makes the {@code DEFAULT_RULE.parameters[]} array
    // the single source of truth for parameter defaults
    // — the checker no longer hard-codes them in its
    // own init() body.
    T def = rules.param(currentRule, name, type);
    return def;
  }

  /** Internal: lookup without rule scope. Kept for back-compat. */
  @SuppressWarnings("unchecked")
  private <T> T legacyLookup(String name, Class<T> type) {
    Object v = paramOverrides.get(name);
    if (v == null) return null;
    return coerce(v, type);
  }

  @SuppressWarnings("unchecked")
  private <T> T coerce(Object v, Class<T> type) {
    if (type.isInstance(v)) return type.cast(v);
    if (v instanceof String s) {
      if (type == Integer.class) return (T) Integer.valueOf(s);
      if (type == Boolean.class) return (T) Boolean.valueOf(s);
    }
    return null;
  }

  public void report(
      int line,
      int col,
      RuleDef rule,
      String message,
      RuleDef.Severity severity,
      String dataflowHead) {
    report(line, col, rule, message, severity, dataflowHead, null);
  }

  /**
   * AST findings need a {@code sinkFunction} on the emitted {@link Finding} so the eval harness's
   * {@code eval_findings_cwe_matched.py} can locate the enclosing method via {@code
   * parse_method_spans}. The DFA path populates {@code sinkFunction} via its flow analysis; the AST
   * path must derive it from the line number by finding the enclosing method declaration in the
   * {@link com.github.javaparser.ast.CompilationUnit}.
   *
   * <p>Lesson 1165: Juliet CWE-585 packs the bad pattern inside a {@code helperBad()} function
   * (called from {@code bad()}) rather than directly in {@code bad()}. Without {@code
   * sinkFunction}, the eval heuristic marks any finding in {@code helperBad()} as "other" (a Juliet
   * helper, not the canonical bad method), which is structurally a false-positive classification
   * even though the finding itself is a true positive for the CWE.
   *
   * <p>This overload accepts an explicit {@code sinkFunction} string. The AST checker should
   * compute it from {@code n.findAncestor(MethodDeclaration.class)} and format it as {@code
   * name(params):returnType}.
   */
  public void report(
      int line,
      int col,
      RuleDef rule,
      String message,
      RuleDef.Severity severity,
      String dataflowHead,
      String sinkFunction) {
    // a rule is dropped from emission when ALL of these
    // are true:
    //   1. The checker's static DEFAULT_RULE has enabled=false
    //   2. The rule id is not in the smoke-test-only forcedRuleIds
    //   3. The RuleRegistry (if it has the rule) says enabled=false
    // A RuleRegistry with `withBuiltinDefaults(true)` (smoke test
    // mode) flips (3) so the rule still emits.
    if (rule != null && !rule.enabled() && !forcedRuleIds.contains(rule.ruleId().id())) {
      if (rules == null) return;
      var inReg = rules.byId(rule.ruleId().id());
      if (inReg.isEmpty() || !inReg.get().enabled()) return;
    }

    // R-PATCH v2: alias resolution. If the rule's id is a legacy
    // (e.g. "pl-0103-style-unused-import" or "bu-1107-hardcoded-credentials"),
    // and the registry has a newer canonical entry with the legacy id
    // in its `aliases` field, substitute the canonical RuleDef before
    // recording the finding. This way existing 564 AST checkers
    // continue to pass their hardcoded DEFAULT_RULE without per-checker
    // migration, but the emitted finding's rule_id is the new
    // canonical plumb.<domain>.<name>.
    RuleDef resolved = resolveViaAlias(rule);

    // also resolve sinkFunction if the AST
    // checker didn't supply one. M2: the
    // enclosing-method lookup is now cached in
    // findEnclosingMethod() — 1 walk per file, not 1
    // per finding. A 1K-line file with 50 findings
    // used to do 50 full-AST walks; now it does 1.
    String resolvedSink = sinkFunction;
    if (resolvedSink == null || resolvedSink.isEmpty()) {
      resolvedSink = findEnclosingMethod(line);
    }

    findings.add(
        new Finding(
            source.path(),
            line,
            col,
            resolved,
            message,
            severity,
            dataflowHead,
            java.util.List.<org.plumb.reporter.FlowStep>of(),
            /* aiVerdict */ null, /* aiReason */
            null,
            resolvedSink));
  }

  /**
   * Find the enclosing top-level method for the given line. M2 cached: the first call walks the
   * CompilationUnit once and stores a sorted list of method ranges; subsequent calls do a binary
   * search. Returns a method signature string (e.g. {@code "bad():void"}) suitable for the eval
   * harness's {@code sink_function} field, or null if no enclosing method is found.
   *
   * <p>Behaviour preserved from the previous implementation: only top-level method declarations
   * (parent is a {@code ClassOrInterfaceDeclaration}, not another method or anonymous initializer)
   * are considered.
   */
  private String findEnclosingMethod(int targetLine) {
    com.github.javaparser.ast.CompilationUnit unit = source.unit();
    if (unit == null) return null;
    buildMethodCacheOnce(unit);
    if (methodRanges == null || methodRanges.isEmpty()) return null;
    // Binary search for the latest method whose start line
    // is <= targetLine. Then verify targetLine is also
    // <= that method's end line — otherwise targetLine
    // sits in a gap between methods (or past the last
    // method) and has no enclosing method.
    int lo = 0;
    int hi = methodRanges.size() - 1;
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (methodRanges.get(mid)[0] <= targetLine) lo = mid;
      else hi = mid - 1;
    }
    int[] r = methodRanges.get(lo);
    if (targetLine < r[0] || targetLine > r[1]) return null;
    return signatureByHash.get(r[2]);
  }

  /**
   * Lazily build the per-file method-range cache. Invalidated whenever the underlying
   * CompilationUnit reference changes (defensive ? production always uses one ctx per source).
   */
  private void buildMethodCacheOnce(com.github.javaparser.ast.CompilationUnit unit) {
    if (methodRangesBuiltFor == unit && methodRanges != null) return;
    methodRanges = new java.util.ArrayList<>();
    signatureByHash = new java.util.HashMap<>();
    methodRangesBuiltFor = unit;
    for (com.github.javaparser.ast.body.MethodDeclaration m :
        unit.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)) {
      // Only top-level method declarations (parent is a
      // ClassOrInterfaceDeclaration, not another method
      // or anonymous initializer) — matches the original
      // filter so behaviour is unchanged.
      java.util.Optional<com.github.javaparser.ast.Node> parent = m.getParentNode();
      if (parent.isEmpty()
          || !(parent.get()
              instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)) {
        continue;
      }
      java.util.Optional<com.github.javaparser.Range> range = m.getRange();
      if (range.isEmpty()) continue;
      int s = range.get().begin.line;
      int e = range.get().end.line;
      String sig = formatSignature(m);
      int hash = sig.hashCode();
      signatureByHash.put(hash, sig);
      methodRanges.add(new int[] {s, e, hash});
    }
    methodRanges.sort((a, b) -> Integer.compare(a[0], b[0]));
  }

  /** Build a {@code name(p1,p2,...):returnType} signature string for a method. */
  private static String formatSignature(com.github.javaparser.ast.body.MethodDeclaration m) {
    StringBuilder sig = new StringBuilder();
    sig.append(m.getNameAsString()).append('(');
    com.github.javaparser.ast.NodeList<com.github.javaparser.ast.body.Parameter> params =
        m.getParameters();
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) sig.append(',');
      sig.append(params.get(i).getType().asString());
    }
    sig.append("):");
    sig.append(m.getType().asString());
    return sig.toString();
  }

  /**
   * Look up the canonical {@link RuleDef} for a given rule via the alias index. Returns the input
   * unchanged if no alias mapping is found (preserves the legacy default behavior).
   */
  private RuleDef resolveViaAlias(RuleDef rule) {
    if (rule == null) return null;
    if (rules == null) return rule;
    String id = rule.ruleId().id();
    if (rules.hasAlias(id)) {
      var byAlias = rules.byAlias(id);
      if (byAlias.isPresent()) return byAlias.get();
    }
    return rule;
  }

  /**
   * the type solver attached to the source's parser, or null if symbol resolution was not enabled.
   */
  public TypeSolver typeSolver() {
    return source.typeSolver();
  }

  /**
   * resolve a {@link Type} AST node to a JavaParser {@link ResolvedType}. Returns null on failure
   * (e.g. type not on the classpath, generic wildcard without bound) and logs a debug line.
   */
  public ResolvedType resolvedTypeOf(Type type) {
    if (type == null) return null;
    try {
      return type.resolve();
    } catch (RuntimeException ex) {
      LOG.debug("resolvedTypeOf({}) failed: {}", type, ex.getMessage());
      return null;
    }
  }

  /**
   * resolve an AST node's "primary type" \u2014 for expressions this is the static type of the
   * expression; for declarations it is the declared type. Returns null on failure.
   */
  public ResolvedType resolvedTypeOf(Node node) {
    if (node == null) return null;
    try {
      if (node instanceof com.github.javaparser.ast.expr.Expression e) {
        return e.calculateResolvedType();
      }
    } catch (RuntimeException ex) {
      LOG.debug(
          "calculateResolvedType on {} failed: {}",
          node.getClass().getSimpleName(),
          ex.getMessage());
    }
    return null;
  }

  /**
   * resolve a name reference (method / variable / type) to its {@link ResolvedDeclaration}. Returns
   * null on failure.
   */
  public ResolvedDeclaration resolveDecl(Node node) {
    if (node == null) return null;
    try {
      if (node instanceof com.github.javaparser.ast.expr.NameExpr n) {
        return n.resolve();
      }
      if (node instanceof com.github.javaparser.ast.expr.MethodCallExpr m) {
        return m.resolve();
      }
      if (node instanceof com.github.javaparser.ast.type.ClassOrInterfaceType t) {
        ResolvedType rt = t.resolve();
        return rt.asReferenceType().getTypeDeclaration().orElse(null);
      }
    } catch (RuntimeException ex) {
      LOG.debug("resolveDecl on {} failed: {}", node.getClass().getSimpleName(), ex.getMessage());
    }
    return null;
  }

  /**
   * describe a resolved type, used in finding messages. Falls back to the type's AST text if
   * resolution fails.
   */
  public String describe(Type type) {
    ResolvedType rt = resolvedTypeOf(type);
    if (rt != null) return rt.describe();
    return type.asString();
  }

  /**
   * the qualified name of a resolved type declaration. Returns the AST name if resolution fails.
   */
  public String qualifiedNameOf(Type type) {
    ResolvedType rt = resolvedTypeOf(type);
    if (rt != null) {
      try {
        ResolvedTypeDeclaration d = rt.asReferenceType().getTypeDeclaration().orElse(null);
        if (d != null) return d.getQualifiedName();
      } catch (RuntimeException ignore) {
        // fall through to AST fallback
      }
    }
    return type.asString();
  }
}

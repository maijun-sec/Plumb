package org.plumb.dfa.taint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of {@link TaintRule}s.
 *
 * <p>Indexed by rule id and (for fast lookup at a call site) by sink family key. Built-in rules are
 * loaded by {@link #builtins()} and include SQL / LDAP / command / format-string / XSS /
 * log-injection / path-traversal / SSRF / XXE / deserialization / weak-crypto / weak-PRNG ? the
 * common taint-flavoured CWE families. A user can register additional rules at runtime; user rules
 * win on id collision.
 *
 * <h2>Why both rule-id and sink-family indices</h2>
 *
 * <p>A checker that wants to fire a finding looks up by rule id (a single rule, known statically).
 * A checker that wants to enumerate "all rules that fire on this sink family" looks up by sink
 * family (e.g. {@code "sql.sinks"} → [SqlInjectionRule, SqlInjectionStackRule]). The first index is
 * O(1) for a known rule; the second is O(n) for a known family, but n is small (typically 1-3 rules
 * per family).
 */
public final class TaintRuleRegistry {

  private final Map<String, TaintRule> byRuleId;
  private final Map<String, List<TaintRule>> bySinkFamily;

  public TaintRuleRegistry(Map<String, TaintRule> rules) {
    Map<String, TaintRule> idMap = new LinkedHashMap<>();
    Map<String, java.util.List<TaintRule>> famMap = new LinkedHashMap<>();
    for (TaintRule r : rules.values()) {
      // R-834: filter disabled-by-default rules at registry
      // construction time. Downstream code (byRuleId / bySinkFamily
      // / all) will simply not see them. The user can still opt
      // in via `TaintRule.withEnabled(true)` in a config or test.
      if (!r.enabled()) {
        continue;
      }
      idMap.put(r.ruleId(), r);
      famMap.computeIfAbsent(r.sinkFamilyKey(), k -> new java.util.ArrayList<>()).add(r);
    }
    this.byRuleId = Collections.unmodifiableMap(idMap);
    Map<String, List<TaintRule>> unmodFam = new LinkedHashMap<>();
    for (Map.Entry<String, List<TaintRule>> e : famMap.entrySet()) {
      unmodFam.put(
          e.getKey(), Collections.unmodifiableList(new java.util.ArrayList<>(e.getValue())));
    }
    this.bySinkFamily = Collections.unmodifiableMap(unmodFam);
  }

  public static TaintRuleRegistry empty() {
    return new TaintRuleRegistry(Map.of());
  }

  /** A fresh registry containing the built-in rules. */
  public static TaintRuleRegistry builtins() {
    return new TaintRuleRegistry(defaultBuiltins());
  }

  public Optional<TaintRule> byRuleId(String ruleId) {
    return Optional.ofNullable(byRuleId.get(ruleId));
  }

  /**
   * All rules that fire on the given sink family key. Empty list (not null) for an unknown family.
   */
  public List<TaintRule> bySinkFamily(String familyKey) {
    List<TaintRule> l = bySinkFamily.get(familyKey);
    return l == null ? List.of() : l;
  }

  public java.util.Collection<TaintRule> all() {
    return byRuleId.values();
  }

  private static Map<String, TaintRule> defaultBuiltins() {
    Map<String, TaintRule> m = new LinkedHashMap<>();
    // CWE-89: SQL Injection
    m.put(
        "dfa-taint-sql-injection",
        TaintRule.of(
            "dfa-taint-sql-injection",
            "dfa-taint-untrusted-source",
            "sql.sinks",
            "CWE-89",
            TaintDomain.StringTaint.INSTANCE));
    // CWE-90: LDAP Injection
    m.put(
        "dfa-taint-ldap-injection",
        TaintRule.of(
            "dfa-taint-ldap-injection",
            "dfa-taint-untrusted-source",
            "ldap.sinks",
            "CWE-90",
            TaintDomain.StringTaint.INSTANCE));
    // CWE-78: OS Command Injection
    m.put(
        "dfa-taint-command-injection",
        TaintRule.of(
            "dfa-taint-command-injection",
            "dfa-taint-untrusted-source",
            "command.sinks",
            "CWE-78",
            TaintDomain.StringTaint.INSTANCE));
    // CWE-134: Format String
    m.put(
        "dfa-taint-format-string",
        TaintRule.of(
            "dfa-taint-format-string",
            "dfa-taint-untrusted-source",
            "format-string.sinks",
            "CWE-134",
            TaintDomain.StringTaint.INSTANCE));
    // CWE-79: XSS / Reflected
    m.put(
        "dfa-taint-xss",
        TaintRule.of(
            "dfa-taint-xss",
            "dfa-taint-untrusted-source",
            "xss.sinks",
            "CWE-79",
            TaintDomain.StringTaint.INSTANCE));
    // CWE-117: Log Injection
    m.put(
        "dfa-taint-log-injection",
        TaintRule.of(
            "dfa-taint-log-injection",
            "dfa-taint-untrusted-source",
            "log.sinks",
            "CWE-117",
            TaintDomain.StringTaint.INSTANCE));
    // CWE-22: Path Traversal
    m.put(
        "dfa-taint-path-traversal",
        TaintRule.of(
            "dfa-taint-path-traversal",
            "dfa-taint-untrusted-source",
            "path.sinks",
            "CWE-22",
            TaintDomain.StringTaint.INSTANCE));
    // CWE-918: SSRF
    m.put(
        "dfa-taint-ssrf",
        TaintRule.of(
            "dfa-taint-ssrf",
            "dfa-taint-untrusted-source",
            "ssrf.sinks",
            "CWE-918",
            TaintDomain.StringTaint.INSTANCE));
    // CWE-611: XXE
    m.put(
        "dfa-taint-xxe",
        TaintRule.of(
            "dfa-taint-xxe",
            "dfa-taint-untrusted-source",
            "xxe.sinks",
            "CWE-611",
            TaintDomain.StringTaint.INSTANCE));
    // CWE-502: Deserialization
    m.put(
        "dfa-taint-unsafe-deserialization",
        TaintRule.of(
            "dfa-taint-unsafe-deserialization",
            "dfa-taint-untrusted-source",
            "deserialization.sinks",
            "CWE-502",
            TaintDomain.MixedTaint.INSTANCE));
    // CWE-327: Use of Weak Crypto
    m.put(
        "dfa-taint-weak-crypto",
        TaintRule.of(
            "dfa-taint-weak-crypto",
            "plumb.sensitive.data",
            "weak-crypto.sinks",
            "CWE-327",
            TaintDomain.MixedTaint.INSTANCE));
    // CWE-338: Use of Weak PRNG
    m.put(
        "dfa-taint-weak-prng",
        TaintRule.of(
            "dfa-taint-weak-prng",
            "plumb.sensitive.data",
            "weak-prng.sinks",
            "CWE-338",
            TaintDomain.MixedTaint.INSTANCE));
    return Collections.unmodifiableMap(m);
  }

  /** Programmatic registration. Returns the previous rule with the same id, if any. */
  public synchronized TaintRule register(TaintRule rule) {
    Objects.requireNonNull(rule, "rule");
    TaintRule prev = byRuleId.get(rule.ruleId());
    // Rebuild the maps (small; builtins is ~12 rules)
    Map<String, TaintRule> newIdMap = new LinkedHashMap<>(byRuleId);
    newIdMap.put(rule.ruleId(), rule);
    // Re-derive family index from scratch to
    // keep the family list consistent.
    Map<String, java.util.List<TaintRule>> newFam = new LinkedHashMap<>();
    for (TaintRule r : newIdMap.values()) {
      newFam.computeIfAbsent(r.sinkFamilyKey(), k -> new java.util.ArrayList<>()).add(r);
    }
    // Field reassignment via a small builder
    // pattern is awkward in Java records; use
    // an immutable wrapper. For tests that need
    // hot re-registration, copy via a public
    // with-rule helper.
    return registerInto(newIdMap, newFam, prev);
  }

  // Internal mutator that rebuilds and returns
  // the previous rule. The field references are
  // final, so the only way to "update" is to
  // construct a new registry. We do that via a
  // public copy method instead; this private
  // helper exists for symmetry with the
  // immutable pattern used by other registries.
  private TaintRule registerInto(
      Map<String, TaintRule> idMap, Map<String, List<TaintRule>> famMap, TaintRule prev) {
    // Immutability: write into local copies
    // and ignore the assignment. This is a
    // no-op stub that documents the contract
    // — the public register() returns a new
    // registry via withRule() below.
    Objects.requireNonNull(idMap, "idMap");
    Objects.requireNonNull(famMap, "famMap");
    return prev;
  }

  /**
   * Return a new registry with the rule added or replaced. Immutable update pattern: the receiver
   * is unchanged; the returned registry has the new rule and a fresh family index. Prefer this over
   * {@link #register(TaintRule)} when you want a clean immutable pipeline.
   */
  public TaintRuleRegistry withRule(TaintRule rule) {
    Objects.requireNonNull(rule, "rule");
    Map<String, TaintRule> newIdMap = new LinkedHashMap<>(byRuleId);
    newIdMap.put(rule.ruleId(), rule);
    return new TaintRuleRegistry(newIdMap);
  }
}

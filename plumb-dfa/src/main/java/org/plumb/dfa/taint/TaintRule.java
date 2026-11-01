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
package org.plumb.dfa.taint;

import java.util.Objects;

/**
 * A single taint-style rule.
 *
 * <p>A {@code TaintRule} binds together everything a taint-style checker needs to fire a finding:
 *
 * <ul>
 *   <li>{@link #ruleId} -- stable id for SARIF and log correlation. The same id should be used by
 *       every emission of this rule so callers can dedup / group.
 *   <li>{@link #sourceKindName} -- the {@code FactKind.name()} that introduces a tainted value
 *       (e.g. {@code "dfa-taint-untrusted-source"} for untrusted input). The rule fires when a
 *       value carrying this kind flows to a sink.
 *   <li>{@link #sinkFamilyKey} -- the {@code SinkFamily.name()} of the sink that consumes the value
 *       (e.g. {@code "sql.sinks"}). The checker uses this to recognise sink calls; a call that
 *       doesn't match a family is ignored.
 *   <li>{@link #cwe} -- the CWE id emitted in the finding (e.g. {@code "CWE-89"} for SQL
 *       injection). Can be {@code null} for rules that don't have a CWE.
 *   <li>{@link #severityLabel} -- the {@code RuleDef.Severity} label ({@code "CRITICAL"} / {@code
 *       "HIGH"} / ...). Defaults to {@code "HIGH"} when null.
 *   <li>{@link #domain} -- the taint domain this rule expects. Mixed when the rule is
 *       domain-agnostic.
 * </ul>
 *
 * <h2>Why a record and not a builder</h2>
 *
 * <p>A rule is a constant: a checker is built around one rule, and the rule doesn't change for the
 * lifetime of the analysis. The record makes the immutability explicit and lets a caller
 * pattern-match on it. The registry ({@link TaintRuleRegistry}) is the lookup table.
 */
public record TaintRule(
    String ruleId,
    String sourceKindName,
    String sinkFamilyKey,
    String cwe,
    String severityLabel,
    TaintDomain domain,
    boolean enabled) {

  public TaintRule {
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(sourceKindName, "sourceKindName");
    Objects.requireNonNull(sinkFamilyKey, "sinkFamilyKey");
    Objects.requireNonNull(domain, "domain");
    // cwe may be null; severityLabel defaults to "HIGH"
  }

  /** Back-compat 5-arg constructor that defaults {@code enabled=true}. */
  public TaintRule(
      String ruleId,
      String sourceKindName,
      String sinkFamilyKey,
      String cwe,
      String severityLabel,
      TaintDomain domain) {
    this(ruleId, sourceKindName, sinkFamilyKey, cwe, severityLabel, domain, true);
  }

  /**
   * Build a rule with the conventional defaults (severity HIGH, domain Mixed, enabled=true).
   * Convenience for tests and for the built-in rule registry.
   */
  public static TaintRule of(
      String ruleId, String sourceKindName, String sinkFamilyKey, String cwe, TaintDomain domain) {
    return new TaintRule(ruleId, sourceKindName, sinkFamilyKey, cwe, "HIGH", domain, true);
  }

  /**
   * build a rule with an explicit {@code enabled} flag. Use {@code false} for rules that have
   * nothing to report on the code they run against, and should be opt-in instead of on by default.
   */
  public static TaintRule of(
      String ruleId,
      String sourceKindName,
      String sinkFamilyKey,
      String cwe,
      TaintDomain domain,
      boolean enabled) {
    return new TaintRule(ruleId, sourceKindName, sinkFamilyKey, cwe, "HIGH", domain, enabled);
  }

  /** copy with {@code enabled} flipped. */
  public TaintRule withEnabled(boolean e) {
    return new TaintRule(ruleId, sourceKindName, sinkFamilyKey, cwe, severityLabel, domain, e);
  }

  /** The {@code RuleDef.Severity} value matching {@link #severityLabel}. */
  public org.plumb.rules.RuleDef.Severity severity() {
    if (severityLabel == null) {
      return org.plumb.rules.RuleDef.Severity.HIGH;
    }
    try {
      return org.plumb.rules.RuleDef.Severity.valueOf(
          severityLabel.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return org.plumb.rules.RuleDef.Severity.HIGH;
    }
  }

  /**
   * The CWE ids this rule carries, in order.
   *
   * <p>A defect class can be numbered by more than one CWE id -- the same path-traversal defect is
   * CWE-22 ("Path Traversal"), CWE-23 ("Relative Path Traversal") and CWE-36 ("Absolute Path
   * Traversal"), and the numbering differs between corpora. A rule keeps ONE id per defect class
   * and carries every number that names it, separated by a comma in the {@code cwe} field.
   *
   * <p>A single id yields a one-element list, so a rule with one label behaves exactly as before.
   */
  public java.util.List<String> cwes() {
    if (cwe == null || cwe.isBlank()) return java.util.List.of();
    java.util.List<String> out = new java.util.ArrayList<>();
    for (String part : cwe.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty() && !out.contains(trimmed)) {
        out.add(trimmed);
      }
    }
    return java.util.List.copyOf(out);
  }

  /**
   * Whether this rule is a match for the given sink family. A rule with sink family {@code
   * "sql.sinks"} only matches calls whose family resolves to {@code "sql.sinks"}; a rule with
   * family {@code "*"} (synthetic) matches anything.
   */
  public boolean matchesSinkFamily(String familyKey) {
    if (sinkFamilyKey.equals("*")) return true;
    return sinkFamilyKey.equals(familyKey);
  }
}

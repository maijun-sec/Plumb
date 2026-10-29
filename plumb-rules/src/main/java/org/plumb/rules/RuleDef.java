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
package org.plumb.rules;

import java.util.List;
import java.util.Objects;

/**
 * Static metadata for a rule: identifier, severity, type, CWE tag, and a human description. Rules
 * also carry a {@code checkerClass} that names the analyzer implementation (DFA checker, style
 * visitor, ...).
 *
 * <p>A3: rules also carry an optional {@code aliases} list -- the canonical rule IDs and names of
 * equivalent rules in other rule sources (PMD, SpotBugs, SonarQube Java). When two sources ship
 * overlapping rules (e.g. PMD {@code EmptyBlock} and SpotBugs {@code
 * SIO_SUPERFLUOUS_INSTANCEOF}-style checks), one is picked as the canonical Plumb rule and the
 * others are recorded as aliases. Reporting code consults {@link #aliases()} to deduplicate and to
 * add a "see also" hint to findings.
 */
public final class RuleDef {

  public enum Severity {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
  }

  public enum Type {
    TAINT,
    STYLE,
    NAMING,
    PERFORMANCE,
    CORRECTNESS,
    RESOURCE,
    SECURITY,
    BUG,
    BAD_PRACTICE,
    MULTITHREADING,
    MALICIOUS_CODE,
    I18N,
    OTHER
  }

  private final RuleId ruleId;
  private final String description;
  private final Severity severity;
  private final Type type;
  private final List<String> cwe;
  private final String checkerClass;
  private final boolean enabled;
  private final List<String> aliases;
  private final List<RuleParameter> parameters;

  public RuleDef(
      RuleId ruleId,
      String description,
      Severity severity,
      Type type,
      List<String> cwe,
      String checkerClass,
      boolean enabled) {
    this(ruleId, description, severity, type, cwe, checkerClass, enabled, List.of(), List.of());
  }

  public RuleDef(
      RuleId ruleId,
      String description,
      Severity severity,
      Type type,
      List<String> cwe,
      String checkerClass,
      boolean enabled,
      List<String> aliases) {
    this(ruleId, description, severity, type, cwe, checkerClass, enabled, aliases, List.of());
  }

  public RuleDef(
      RuleId ruleId,
      String description,
      Severity severity,
      Type type,
      List<String> cwe,
      String checkerClass,
      boolean enabled,
      List<String> aliases,
      List<RuleParameter> parameters) {
    this.ruleId = Objects.requireNonNull(ruleId);
    this.description = Objects.requireNonNull(description);
    this.severity = Objects.requireNonNull(severity);
    this.type = Objects.requireNonNull(type);
    this.cwe = List.copyOf(cwe);
    this.checkerClass = Objects.requireNonNull(checkerClass);
    this.enabled = enabled;
    this.aliases = aliases == null ? List.of() : List.copyOf(aliases);
    this.parameters = parameters == null ? List.of() : List.copyOf(parameters);
  }

  public RuleId ruleId() {
    return ruleId;
  }

  public String description() {
    return description;
  }

  public Severity severity() {
    return severity;
  }

  public Type type() {
    return type;
  }

  public List<String> cwe() {
    return cwe;
  }

  public String checkerClass() {
    return checkerClass;
  }

  public boolean enabled() {
    return enabled;
  }

  /** A3: alias list of equivalent rules in other rule sources. */
  public List<String> aliases() {
    return aliases;
  }

  /** Configurable parameters for this rule. */
  public List<RuleParameter> parameters() {
    return parameters;
  }

  public RuleDef withEnabled(boolean e) {
    return new RuleDef(
        ruleId, description, severity, type, cwe, checkerClass, e, aliases, parameters);
  }

  /** A3: returns a copy with the given aliases list. */
  public RuleDef withAliases(List<String> newAliases) {
    return new RuleDef(
        ruleId, description, severity, type, cwe, checkerClass, enabled, newAliases, parameters);
  }

  /** Returns a copy with the given parameters list. */
  public RuleDef withParameters(List<RuleParameter> newParameters) {
    return new RuleDef(
        ruleId, description, severity, type, cwe, checkerClass, enabled, aliases, newParameters);
  }
}

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
package org.plumb.dfa.config;

import java.util.Locale;
import java.util.Objects;

/**
 * Per-checker configuration.
 *
 * <p>Loaded from {@code checkers.yaml}; can be overridden at runtime via {@link
 * CheckerRegistry#register(CheckerConfig)} or by passing a custom YAML to {@link
 * ConfigLoader#loadFromDirectory}.
 */
public final class CheckerConfig {

  private final String name;
  private final boolean enabled;
  private final String severityLabel; // "LOW" / "MEDIUM" / ...
  private final String cwe; // "CWE-NNN" or null
  private final int contextDepth; // 0/1/2/3
  private final String pathSensitivity; // "full" / "windowed" / "none"
  private final String taintDomain; // "STRING" / "NUMERIC" / "POINTER" / "MIXED" / null
  private final String className; // FQCN of the checker implementation
  private final String description;

  /**
   * The languages this checker applies to.
   *
   * <p>An EMPTY set means "every language" -- the conservative default, and the one a checker that
   * has no language-specific mechanism should carry.
   *
   * <p><b>Why this is on the CONFIG and not only on the rule.</b> A checker's language scope has
   * two independent sources, and they can disagree:
   *
   * <ul>
   *   <li>The RULE's scope says which languages the <em>defect class</em> is expressed in. A
   *       checker that watches {@code jni.sinks} is Java-shaped because the APIs it matches ({@code
   *       java.lang.System.load}) exist only on the JVM.
   *   <li>The CONFIG's scope says which languages the checker is <em>mechanism</em> for. A checker
   *       that hardcodes {@code java.lang.Math.min} in its body is Java-shaped by its own code,
   *       whatever the rule says.
   * </ul>
   *
   * <p>Both are honoured, and the effective scope is the INTERSECTION: a checker loads only when
   * the run selected every language the rule names AND the checker names. A checker whose rule says
   * "every language" but whose config says {@code [java]} is still Java-only.
   *
   * <p><b>Directional rule.</b> This selects which checkers run against which code. It never
   * establishes that code is safe -- a checker that does not apply simply produces no findings for
   * that language, which is different from "the language is clean".
   */
  private final java.util.Set<String> languages;

  /**
   * analysis-style aggressiveness knob. One of {@code "low"} / {@code "medium"} / {@code "high"}.
   *
   * <ul>
   *   <li><b>low</b> — most precise, fewer false positives, but more false negatives (skips
   *       ambiguous patterns, requires stronger evidence before firing).
   *   <li><b>medium</b> — default balance; same defaults the baseline uses for its baseline checker
   *       set.
   *   <li><b>high</b> — more aggressive, more false positives, fewer false negatives (broader
   *       patterns, more cross-function assumptions, longer lookback windows).
   * </ul>
   *
   * <p>Inspired by the reference's {@code --aggressiveness-level} / {@code
   * --webapp-security-aggressiveness-level} command-line option, but expressed as a per-checker
   * field so different checkers can have different defaults.
   */
  private final String aggressiveness;

  public CheckerConfig(
      String name,
      boolean enabled,
      String severityLabel,
      String cwe,
      int contextDepth,
      String pathSensitivity,
      String taintDomain,
      String className,
      String description) {
    this(
        name,
        enabled,
        severityLabel,
        cwe,
        contextDepth,
        pathSensitivity,
        taintDomain,
        className,
        description,
        "medium");
  }

  public CheckerConfig(
      String name,
      boolean enabled,
      String severityLabel,
      String cwe,
      int contextDepth,
      String pathSensitivity,
      String taintDomain,
      String className,
      String description,
      String aggressiveness) {
    this(
        name,
        enabled,
        severityLabel,
        cwe,
        contextDepth,
        pathSensitivity,
        taintDomain,
        className,
        description,
        aggressiveness,
        java.util.Set.of());
  }

  public CheckerConfig(
      String name,
      boolean enabled,
      String severityLabel,
      String cwe,
      int contextDepth,
      String pathSensitivity,
      String taintDomain,
      String className,
      String description,
      String aggressiveness,
      java.util.Set<String> languages) {
    this.name = Objects.requireNonNull(name, "name");
    this.enabled = enabled;
    this.severityLabel = severityLabel;
    this.cwe = cwe;
    this.contextDepth = contextDepth;
    this.pathSensitivity = pathSensitivity;
    this.taintDomain = taintDomain;
    this.className = className;
    this.description = description == null ? "" : description;
    this.aggressiveness = normaliseAggressiveness(aggressiveness);
    this.languages =
        languages == null ? java.util.Set.of() : java.util.Collections.unmodifiableSet(languages);
  }

  public String name() {
    return name;
  }

  public boolean enabled() {
    return enabled;
  }

  public String severityLabel() {
    return severityLabel;
  }

  public String cwe() {
    return cwe;
  }

  public int contextDepth() {
    return contextDepth;
  }

  public String pathSensitivity() {
    return pathSensitivity;
  }

  public String taintDomain() {
    return taintDomain;
  }

  public String className() {
    return className;
  }

  public String description() {
    return description;
  }

  /**
   * The languages this checker applies to.
   *
   * <p>Empty means "every language". The names are the {@link SourceLanguage} enum names in lower
   * case, which is also what the YAML uses.
   *
   * @return the languages, or an empty set for "every language"
   */
  public java.util.Set<String> languages() {
    return languages;
  }

  /**
   * Whether this checker applies to a language.
   *
   * @param language the language
   * @return true when the checker applies, or when it applies to every language
   */
  public boolean appliesTo(org.plumb.tac.ir.SourceLanguage language) {
    if (languages.isEmpty()) return true;
    if (languages.contains(ConfigLoader.ALL_LANGUAGES)) return true;
    if (language == null) return false;
    return languages.contains(language.name())
        || languages.contains(language.name().toLowerCase(java.util.Locale.ROOT));
  }

  /**
   * analysis-style aggressiveness knob: {@code "low"} / {@code "medium"} / {@code "high"}. Defaults
   * to {@code "medium"}.
   */
  public String aggressiveness() {
    return aggressiveness;
  }

  /**
   * True if the configured aggressiveness is at or above the requested level. Use as {@code
   * config.isAtLeast("high")} to gate "high only" branches.
   */
  public boolean isAtLeast(String level) {
    if (level == null) return true;
    return rank(this.aggressiveness) >= rank(normaliseAggressiveness(level));
  }

  private static int rank(String level) {
    if ("high".equals(level)) return 2;
    if ("medium".equals(level)) return 1;
    return 0; // "low" or unknown
  }

  /**
   * Lower-case + reject garbage values (anything that isn't low/medium/high becomes "medium"). Lets
   * the YAML loader hand in raw user strings without breaking the engine.
   */
  private static String normaliseAggressiveness(String v) {
    if (v == null) return "medium";
    String s = v.toLowerCase(Locale.ROOT).trim();
    if ("low".equals(s) || "medium".equals(s) || "high".equals(s)) {
      return s;
    }
    return "medium";
  }
}

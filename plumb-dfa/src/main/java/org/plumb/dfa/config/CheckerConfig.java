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
   * Coverity-style aggressiveness knob. One of {@code "low"} / {@code "medium"} / {@code "high"}.
   *
   * <ul>
   *   <li><b>low</b> — most precise, fewer false positives, but more false negatives (skips
   *       ambiguous patterns, requires stronger evidence before firing).
   *   <li><b>medium</b> — default balance; same defaults Coverity uses for its baseline checker
   *       set.
   *   <li><b>high</b> — more aggressive, more false positives, fewer false negatives (broader
   *       patterns, more cross-function assumptions, longer lookback windows).
   * </ul>
   *
   * <p>Inspired by Coverity's {@code --aggressiveness-level} / {@code
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
   * Coverity-style aggressiveness knob: {@code "low"} / {@code "medium"} / {@code "high"}. Defaults
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

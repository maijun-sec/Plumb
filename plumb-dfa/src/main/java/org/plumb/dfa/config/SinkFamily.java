package org.plumb.dfa.config;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Sink/source family definition.
 *
 * <p>A family groups function-name patterns that share a CWE classification. For example, the
 * {@code sql.sinks} family matches {@code java.sql.Statement#execute}, {@code
 * java.sql.PreparedStatement#execute}, etc., and is tagged {@code CWE-89}.
 *
 * <p>Loaded from {@code sink-families.yaml}; can also be registered programmatically via {@link
 * SinkFamilyRegistry#register}.
 *
 * <p>Each pattern may carry an optional {@code argIndex} override that names the user-visible Java
 * argument index holding the tainted value. The family's {@link #defaultArgIndex()} applies when a
 * pattern's index is unset. R693 moved the per-checker {@code sinkArgumentIndex()} override out of
 * the checker layer and into the pattern definition so that a single family (e.g. {@code
 * header.sinks}) can mix patterns whose tainted value sits at user-arg 0 ({@code sendRedirect})
 * with patterns whose tainted value sits at user-arg 1 ({@code setHeader}).
 *
 * <h2>R1099: receiver-tainted sinks</h2>
 *
 * <p>{@link SinkPattern#RECEIVER_ARG_INDEX} ({@code -2}) declares that the tainted value is the
 * <em>receiver</em>, not a user argument. {@code ObjectInputStream#readObject()} is the motivating
 * case: it takes zero arguments, and the untrusted data is in the stream itself. Before this
 * sentinel existed, such a pattern fell back to {@link #defaultArgIndex()} ({@code 0}, "first
 * user argument") and indexed past the end of an empty argument list — so {@code
 * TAINT_SINK_UNSAFE_DESERIALIZE} was registered CRITICAL yet was structurally unreachable. That is
 * worse than having no rule, because the coverage report claims the CWE is handled.
 */
public final class SinkFamily {

  /**
   * Sentinel {@code argIndex} meaning "the tainted value is the call's receiver".
   *
   * <p>Distinct from {@code -1} ("use the family default") and from any valid user-arg index
   * ({@code >= 0}).
   */
  public static final int RECEIVER_ARG_INDEX = -2;

  /**
   * One pattern in a {@link SinkFamily}. The regex matches as a substring (via {@link
   * java.util.regex.Matcher#find()}) of the callee signature. {@code argIndex} names the
   * user-visible Java argument index that carries the tainted value: {@code -1} means "use the
   * family default", and {@link #RECEIVER_ARG_INDEX} ({@code -2}) means "the receiver".
   */
  public static final class SinkPattern {
    private final String pattern;
    private final int argIndex; // -1 = use family default, -2 = receiver
    private final Pattern compiled;

    public SinkPattern(String pattern, int argIndex) {
      this.pattern = Objects.requireNonNull(pattern, "pattern");
      this.argIndex = argIndex;
      this.compiled = Pattern.compile(pattern);
    }

    public String pattern() {
      return pattern;
    }

    /**
     * Returns the user-arg index this pattern uses, {@link #RECEIVER_ARG_INDEX} when the tainted
     * value is the receiver, or {@code -1} if it falls back to the family default.
     */
    public int argIndex() {
      return argIndex;
    }

    /** True when this pattern explicitly names the receiver as the tainted value. */
    public boolean isReceiverTainted() {
      return argIndex == RECEIVER_ARG_INDEX;
    }

    public boolean hasExplicitArgIndex() {
      return argIndex >= 0 || argIndex == RECEIVER_ARG_INDEX;
    }

    public Pattern compiled() {
      return compiled;
    }
  }

  private final String name;
  private final String cwe; // "CWE-89" or null
  private final String severityLabel; // "CRITICAL" or null
  private final boolean enabled;
  private final int defaultArgIndex; // family-wide arg index
  private final List<SinkPattern> patterns;

  public SinkFamily(
      String name, String cwe, String severityLabel, boolean enabled, List<String> patterns) {
    // R693.5: legacy string-list constructor.
    // Default to -1 (no opinion) so the
    // TaintedCheckerBase.patternArgIndex helper
    // falls back to the checker's
    // sinkArgumentIndex() override. Pre-R693.5
    // this hardcoded 0, which silently broke
    // XssInjectionChecker.sinkArgumentIndex=1
    // for the legacy-form xss.sinks family
    // (the family always said "arg 0" before
    // checking the checker override).
    this(name, cwe, severityLabel, enabled, -1, toPatternList(patterns));
  }

  /**
   * R693: full constructor. Pass {@link SinkPattern} entries whose {@code argIndex} carries the
   * per-pattern override; use {@code -1} to fall back to the family-wide {@code defaultArgIndex}.
   */
  public SinkFamily(
      String name,
      String cwe,
      String severityLabel,
      boolean enabled,
      int defaultArgIndex,
      List<SinkPattern> patterns) {
    this.name = Objects.requireNonNull(name, "name");
    this.cwe = cwe;
    this.severityLabel = severityLabel;
    this.enabled = enabled;
    this.defaultArgIndex = defaultArgIndex;
    this.patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns"));
  }

  private static List<SinkPattern> toPatternList(List<String> strs) {
    return strs.stream().map(p -> new SinkPattern(p, -1)).toList();
  }

  public String name() {
    return name;
  }

  public String cwe() {
    return cwe;
  }

  public String severityLabel() {
    return severityLabel;
  }

  public boolean enabled() {
    return enabled;
  }

  /**
   * Family-wide default arg index. Used when a pattern's own {@link SinkPattern#argIndex()} is
   * {@code -1} (unset). Defaults to {@code 0} (first user-visible arg after the receiver) to match
   * Plumb's pre-R693 default.
   */
  public int defaultArgIndex() {
    return defaultArgIndex;
  }

  public List<SinkPattern> patterns() {
    return patterns;
  }

  /**
   * Returns the first {@link SinkPattern} whose regex matches as a substring of the given callee
   * signature, or {@code null} if no pattern matches.
   *
   * <p>Callers should use {@link #argIndexFor} (or compute via {@code pattern.argIndex() >= 0 ?
   * pattern.argIndex() : defaultArgIndex()}) to read the per-pattern override.
   */
  public SinkPattern matchPattern(String calleeSignature) {
    if (!enabled) return null;
    for (SinkPattern p : patterns) {
      if (p.compiled.matcher(calleeSignature).find()) return p;
    }
    return null;
  }

  /**
   * Convenience: returns true iff some pattern matches the callee signature. Equivalent to {@code
   * matchPattern(sig) != null}.
   */
  public boolean matches(String calleeSignature) {
    return matchPattern(calleeSignature) != null;
  }

  /**
   * Resolve the effective user-arg index for the given callee signature. Returns the matching
   * pattern's explicit argIndex, or the family default if no pattern has one. Returns {@code -1} if
   * no pattern matches.
   */
  public int argIndexFor(String calleeSignature) {
    SinkPattern p = matchPattern(calleeSignature);
    if (p == null) return -1;
    return p.hasExplicitArgIndex() ? p.argIndex() : defaultArgIndex;
  }

  /**
   * Returns the CWE family for a finding that flows into this sink. {@code null} if the family is
   * not CWE-tagged (e.g. taint sources).
   */
  public String cweOrNull() {
    return cwe;
  }
}

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
 * pattern's index is unset. moved the per-checker {@code sinkArgumentIndex()} override out of the
 * checker layer and into the pattern definition so that a single family (e.g. {@code header.sinks})
 * can mix patterns whose tainted value sits at user-arg 0 ({@code sendRedirect}) with patterns
 * whose tainted value sits at user-arg 1 ({@code setHeader}).
 *
 * <h2>receiver-tainted sinks</h2>
 *
 * <p>{@link SinkPattern#RECEIVER_ARG_INDEX} ({@code -2}) declares that the tainted value is the
 * <em>receiver</em>, not a user argument. {@code ObjectInputStream#readObject()} is the motivating
 * case: it takes zero arguments, and the untrusted data is in the stream itself. Before this
 * sentinel existed, such a pattern fell back to {@link #defaultArgIndex()} ({@code 0}, "first user
 * argument") and indexed past the end of an empty argument list — so {@code
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

    /**
     * The same sink, written so that the class-name prefix is optional.
     *
     * <p>Why this exists. The TAC callee signature carries a fully qualified receiver type ({@code
     * java.sql.Statement.executeQuery/1}) only when the symbol solver can resolve the receiver's
     * type. When it cannot -- which is the normal case for a third-party type whose jar is not on
     * the analysis classpath -- the IR keeps the simple name ({@code JdbcTemplate.query/2}). A
     * pattern anchored to a fully qualified name ({@code
     * ^org\.springframework\.jdbc\.core\.JdbcTemplate\.query}) then matches nothing, and every
     * framework sink -- Spring's JdbcTemplate, MyBatis, Jackson, Guava -- becomes unreachable
     * without any error.
     *
     * <p>So each pattern also gets a "prefix optional" variant, derived mechanically from the
     * pattern text: the leading {@code ^} and the dotted package prefix become optional. The
     * relaxed variant is only consulted for a callee whose type part is a bare simple name, and it
     * is a fallback: the fully qualified form always wins when it matches.
     *
     * <p>The relaxation is deliberately narrow. It only relaxes the PACKAGE prefix of the receiver
     * class; the class name itself and the method name must still match exactly, so {@code
     * JdbcTemplate.query} cannot be matched by a pattern for {@code NamedParameterJdbcTemplate} or
     * by a pattern for some unrelated {@code query} method.
     */
    private final Pattern relaxed;

    public SinkPattern(String pattern, int argIndex) {
      this.pattern = Objects.requireNonNull(pattern, "pattern");
      this.argIndex = argIndex;
      this.compiled = Pattern.compile(pattern);
      this.relaxed = Pattern.compile(relaxedForm(pattern));
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

    /** The prefix-optional variant of this pattern; see the field's javadoc. */
    public Pattern relaxed() {
      return relaxed;
    }

    /**
     * Derive the prefix-optional variant of a sink pattern.
     *
     * <p>Input patterns look like {@code
     * ^org\.springframework\.jdbc\.core\.JdbcTemplate\.query(/\d+)?} -- a leading {@code ^}, a
     * fully qualified class name whose separators are escaped dots, then an escaped dot, then the
     * method name.
     *
     * <p>The relaxed form makes the PACKAGE prefix optional while keeping the class name and the
     * method name mandatory:
     *
     * <pre>
     *   ^org\.springframework\.jdbc\.core\.JdbcTemplate\.query(/\d+)?
     *   -&gt; ^(?:[\w.]+\.)?JdbcTemplate\.query(/\d+)?
     * </pre>
     *
     * <p>Only the leading package segments are dropped: the last capitalised identifier before the
     * method name is the CLASS and stays required. That is what keeps the relaxation from turning
     * {@code JdbcTemplate.query} into a match for some other {@code query} method.
     *
     * <p>The relaxed form also requires a WORD BOUNDARY after the method name. Sink patterns are
     * matched as substrings, so without a boundary the pattern for {@code JdbcTemplate.query} would
     * also match {@code JdbcTemplate.queryForList} and {@code JdbcTemplate.queryForObject} -- which
     * are different sinks with different argument indexes. The strict form does not need the
     * boundary because the fully qualified prefix is already distinctive; the relaxed form is the
     * one that needs it.
     *
     * <p>A pattern that does not have the {@code ^fully\.qualified\.Class\.method} shape is
     * returned unchanged -- there is nothing to relax, and guessing would be unsafe.
     */
    public static String relaxedForm(String pattern) {
      if (pattern == null) return "";
      if (!pattern.startsWith("^")) return pattern;
      // Locate the class segment: the last capitalised identifier that is followed by an escaped
      // dot and then the method name.
      java.util.regex.Matcher classSegment = CLASS_SEGMENT.matcher(pattern);
      if (!classSegment.find()) return pattern;
      String prefix = pattern.substring(0, classSegment.start());
      String classAndMethod = pattern.substring(classSegment.start());
      // The class segment itself must be a bare identifier (no regex metacharacters) for the
      // relaxation to be safe. If it contains anything else, leave the pattern alone.
      if (!BARE_IDENTIFIER.matcher(classSegment.group(1)).matches()) return pattern;
      // "prefix" is the leading '^' plus the escaped package segments.
      if (!PACKAGE_PREFIX.matcher(prefix).matches()) return pattern;
      String relaxed = "^(?:[\\w.]+\\.)?" + classAndMethod;
      // Insert a word boundary right after the method name, so that a pattern
      // for "query" does not also match "queryForList".
      return withMethodWordBoundary(relaxed, classAndMethod);
    }

    /**
     * Insert a {@code \b} after the method name of a relaxed pattern, if the method name is not
     * already followed by a boundary.
     *
     * <p>The method name is the identifier that follows the class segment's escaped dot. The tail
     * after it is the arity group ({@code (/\d+)?}) and anything else the pattern carries.
     */
    private static String withMethodWordBoundary(String relaxed, String classAndMethod) {
      java.util.regex.Matcher methodSegment = METHOD_SEGMENT.matcher(classAndMethod);
      if (!methodSegment.find()) return relaxed;
      int methodEnd = methodSegment.end();
      String method = methodSegment.group(1);
      String tail = classAndMethod.substring(methodEnd);
      // Already bounded (e.g. the pattern spells its own boundary) -- leave it alone.
      if (tail.startsWith("\\b") || tail.startsWith("(?:\\b")) return relaxed;
      return relaxed.substring(0, relaxed.length() - tail.length()) + "\\b" + tail;
    }

    /**
     * Matches the method name of a sink pattern: the identifier that follows the class segment's
     * escaped dot, up to the first character that is not part of an identifier.
     */
    private static final java.util.regex.Pattern METHOD_SEGMENT =
        java.util.regex.Pattern.compile("\\.([A-Za-z_$][A-Za-z0-9_$]*)");

    /**
     * Matches the class segment of a fully qualified sink pattern: the last capitalised identifier
     * that is followed by an escaped dot and a method name.
     */
    private static final java.util.regex.Pattern CLASS_SEGMENT =
        java.util.regex.Pattern.compile("([A-Z][A-Za-z0-9_$]*)\\\\\\.");

    /** A bare Java identifier, with no regex metacharacters. */
    private static final java.util.regex.Pattern BARE_IDENTIFIER =
        java.util.regex.Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /**
     * The leading part of the pattern: a {@code ^} followed by escaped dotted package segments
     * (each segment a lowercase-ish identifier). Anything else means the pattern is not the fully
     * qualified shape this relaxation understands.
     */
    private static final java.util.regex.Pattern PACKAGE_PREFIX =
        java.util.regex.Pattern.compile("\\^((?:[A-Za-z_$][A-Za-z0-9_$]*\\\\\\.)+)");
  }

  private final String name;
  private final String cwe; // "CWE-89" or null
  private final String severityLabel; // "CRITICAL" or null
  private final boolean enabled;
  private final int defaultArgIndex; // family-wide arg index
  private final List<SinkPattern> patterns;

  public SinkFamily(
      String name, String cwe, String severityLabel, boolean enabled, List<String> patterns) {
    // legacy string-list constructor.
    // Default to -1 (no opinion) so the
    // TaintedCheckerBase.patternArgIndex helper
    // falls back to the checker's
    // sinkArgumentIndex() override. Pre-
    // this hardcoded 0, which silently broke
    // XssInjectionChecker.sinkArgumentIndex=1
    // for the legacy-form xss.sinks family
    // (the family always said "arg 0" before
    // checking the checker override).
    this(name, cwe, severityLabel, enabled, -1, toPatternList(patterns));
  }

  /**
   * full constructor. Pass {@link SinkPattern} entries whose {@code argIndex} carries the
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
   * Plumb's pre- default.
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
   *
   * <p>When the callee's receiver type is a bare simple name -- which is what the TAC emits for a
   * type the symbol solver could not resolve, e.g. a third-party class whose jar is not on the
   * analysis classpath -- the fully qualified form of the pattern cannot match. In that case the
   * pattern's prefix-optional variant is tried as well, so framework sinks stay reachable. A callee
   * whose receiver type is already fully qualified is matched by the strict form only, so an exact
   * fully qualified name always wins over the relaxed fallback.
   */
  public SinkPattern matchPattern(String calleeSignature) {
    if (!enabled) return null;
    boolean simpleName = hasSimpleNameReceiver(calleeSignature);
    for (SinkPattern p : patterns) {
      if (p.compiled.matcher(calleeSignature).find()) return p;
    }
    if (!simpleName) return null;
    for (SinkPattern p : patterns) {
      if (p.relaxed.matcher(calleeSignature).find()) return p;
    }
    return null;
  }

  /**
   * True when the callee signature's receiver type is a bare simple name (no dot), i.e. the symbol
   * solver did not resolve it to a fully qualified type.
   *
   * <p>The signature shape is {@code Type.method/arity}:
   *
   * <pre>
   *   JdbcTemplate.query/2             -&gt; receiver type "JdbcTemplate"      (simple)
   *   java.sql.Statement.executeQuery/1 -&gt; receiver type "java.sql.Statement" (qualified)
   *   hasNext/0                        -&gt; no receiver type at all
   * </pre>
   *
   * <p>So the type part is what remains after dropping the trailing method name and the arity.
   */
  private static boolean hasSimpleNameReceiver(String calleeSignature) {
    if (calleeSignature == null) return false;
    int slash = calleeSignature.lastIndexOf('/');
    String typeAndMethod = slash < 0 ? calleeSignature : calleeSignature.substring(0, slash);
    int dot = typeAndMethod.lastIndexOf('.');
    if (dot < 0) return false; // no receiver type at all (e.g. "hasNext/0")
    String receiverType = typeAndMethod.substring(0, dot);
    return receiverType.indexOf('.') < 0;
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

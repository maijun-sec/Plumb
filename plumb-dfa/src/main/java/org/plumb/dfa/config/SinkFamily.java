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
import java.util.Set;
import java.util.regex.Pattern;
import org.plumb.tac.ir.SourceLanguage;

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
   *
   * <h2>several tainted argument positions</h2>
   *
   * <p>A sink symbol can carry the taint at MORE THAN ONE argument position, and a single {@code
   * argIndex} cannot express that.
   *
   * <p>The concrete case is Spring's {@code JdbcTemplate}. It has two overload families for the
   * same method name:
   *
   * <pre>
   *   queryForList(String sql, RowMapper mapper)   -- the tainted value is the SQL, index 0
   *   queryForList(String sql, Object... args)     -- the tainted values are the varargs, index 1+
   * </pre>
   *
   * <p>Both are real sinks, and they are real vulnerabilities. A single index forces a choice
   * between them, and the choice is made by LIST ORDER -- so the answer changes when the catalog is
   * regenerated. That is not a precision trade-off, it is a coin flip: the wrong index reports the
   * {@code RowMapper} as tainted, or misses the tainted SQL entirely.
   *
   * <p>So a pattern carries a SET of candidate positions, and the checker reports a finding when
   * ANY of them is tainted. The single-index form is the one-element case, so nothing that worked
   * before changes.
   */
  public static final class SinkPattern {
    private final String pattern;
    private final int argIndex; // -1 = use family default, -2 = receiver

    /**
     * Every argument position this pattern names as carrying the taint.
     *
     * <p>Always contains {@link #argIndex} when that index is explicit. The receiver sentinel
     * ({@code -2}) may appear alongside real indices: a call can be a sink because the receiver is
     * tainted, and because an argument is tainted.
     */
    private final int[] argIndexes;

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

    /**
     * The receiver class name, when the pattern names a BARE class (no package).
     *
     * <p><b>Why this exists.</b> A bare-class pattern ({@code ^Statement\.execute}) is the
     * hand-written shorthand for "this sink, whatever package the receiver came from". The matcher
     * compares with {@link java.util.regex.Matcher#find()}, so the pattern matches wherever the
     * text occurs -- and {@code java.sql.Statement.execute/1} CONTAINS the text {@code
     * Statement.execute}. The bare pattern therefore claims a call it was never meant to name:
     * {@code java.sql.Statement#execute} is a SQL sink, while {@code java.beans.Statement#execute}
     * is a code-execution sink, and the bare form cannot tell them apart.
     *
     * <p>So a bare-class pattern is only allowed to match a callee whose receiver is EXACTLY that
     * simple name. {@code Statement.execute/1} matches; {@code java.sql.Statement.execute/1} does
     * not -- the fully qualified form has its own pattern, and that one is the one that decides.
     *
     * <p>A pattern that names a package (the normal case) has no bare class and is unaffected.
     */
    private final String bareClassName;

    public SinkPattern(String pattern, int argIndex) {
      this(pattern, argIndex, new int[] {argIndex});
    }

    /**
     * A pattern that names SEVERAL argument positions as carrying the taint.
     *
     * <p>See the class javadoc for why a symbol can have more than one. The array is deduplicated
     * and kept in the order given; {@code -1} (the family default) is dropped, because "the family
     * default" is not a position -- it is an instruction to look one up.
     *
     * @param pattern the callee-signature regex
     * @param argIndex the primary index, kept for the single-index accessors
     * @param argIndexes every candidate position; the primary is added if missing
     */
    public SinkPattern(String pattern, int argIndex, int[] argIndexes) {
      this.pattern = Objects.requireNonNull(pattern, "pattern");
      this.argIndex = argIndex;
      this.compiled = Pattern.compile(pattern);
      this.relaxed = Pattern.compile(relaxedForm(pattern));
      this.bareClassName = bareClassName(pattern);
      this.argIndexes = normaliseIndexes(argIndex, argIndexes);
    }

    /**
     * The candidate positions, deduplicated, with {@code -1} dropped.
     *
     * <p>The primary index is always present, so a pattern built with the single-index constructor
     * has exactly one candidate.
     */
    private static int[] normaliseIndexes(int argIndex, int[] argIndexes) {
      java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
      if (argIndex != -1) out.add(argIndex);
      if (argIndexes != null) {
        for (int i : argIndexes) {
          if (i == -1) continue;
          if (!out.contains(i)) out.add(i);
        }
      }
      if (out.isEmpty()) out.add(-1);
      int[] arr = new int[out.size()];
      for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
      return arr;
    }

    /**
     * Every argument position this pattern names as carrying the taint.
     *
     * <p>Never empty. A pattern whose index is unset carries {@code -1} ("use the family default").
     */
    public int[] argIndexes() {
      return argIndexes.clone();
    }

    /**
     * Whether this pattern names MORE THAN ONE candidate position.
     *
     * <p>A single-position pattern resolves exactly as it always did.
     */
    public boolean hasMultipleArgIndexes() {
      return argIndexes.length > 1;
    }

    /**
     * The bare receiver class name this pattern names, or {@code null} when the pattern names a
     * qualified receiver.
     *
     * <p>A pattern is "bare" when it looks like {@code ^Class.method} -- a single identifier
     * followed by a separator and the method name -- with no package qualifier and no alternation
     * group around the owner.
     *
     * <p>The parse walks the pattern's owner segments: each is an identifier followed by a
     * separator. The LAST owner segment is the class; the ones before it are the package. A pattern
     * is bare exactly when there is only ONE owner segment.
     */
    private static String bareClassName(String pattern) {
      if (pattern == null || !pattern.startsWith("^")) return null;
      int i = 1;
      java.util.ArrayList<String> owners = new java.util.ArrayList<>();
      while (true) {
        int start = i;
        while (i < pattern.length() && isIdentifierPart(pattern.charAt(i))) i++;
        if (i == start) return null; // no identifier -- not the shape we understand
        owners.add(pattern.substring(start, i));
        // The separator: a literal dot or an escaped dot.
        if (i < pattern.length() && pattern.charAt(i) == '\\') i++;
        if (i >= pattern.length() || pattern.charAt(i) != '.') return null;
        i++;
        // An alternation group right after the separator means the owner is a
        // regex with alternatives -- not a bare class.
        if (i < pattern.length() && pattern.charAt(i) == '(') return null;
        // If another identifier follows, that identifier is the next owner
        // segment -- unless it is the method name. The method name is the one
        // followed by the arity group or the end of the pattern.
        int nextStart = i;
        while (i < pattern.length() && isIdentifierPart(pattern.charAt(i))) i++;
        if (i == nextStart) return null;
        String next = pattern.substring(nextStart, i);
        // The method name is followed by `(`, `)`, `?`, `*`, `+`, `$`, or the end.
        if (i >= pattern.length()) {
          // `^Class.method` -- the method is the last thing.
          owners.add(next);
          break;
        }
        char after = pattern.charAt(i);
        if (after == '(' || after == ')' || after == '?' || after == '*' || after == '+') {
          // `^Class.method(arity)?` -- the method name is the last owner segment.
          owners.add(next);
          break;
        }
        // Otherwise this identifier is another owner segment (a package part).
        owners.add(next);
        if (i < pattern.length() && pattern.charAt(i) == '\\') i++;
        if (i >= pattern.length() || pattern.charAt(i) != '.') return null;
        i++;
      }
      // owners = [pkg..., class, method]
      if (owners.size() != 2) return null; // a package qualifier -> not bare
      return owners.get(0);
    }

    /** True when the character can appear in a Java identifier. */
    private static boolean isIdentifierPart(char c) {
      return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * The bare receiver class name this pattern names, or {@code null}.
     *
     * @see #bareClassName(String)
     */
    public String bareClassName() {
      return bareClassName;
    }

    /**
     * Whether this pattern names a bare receiver class (no package qualifier).
     *
     * @see #bareClassName(String)
     */
    public boolean isBareClass() {
      return bareClassName != null;
    }

    /**
     * Whether this pattern matches the given callee signature.
     *
     * <p>A bare-class pattern only matches a callee whose receiver type is exactly the simple name
     * it names; see {@link #bareClassName} for why.
     */
    public boolean matches(String calleeSignature) {
      if (calleeSignature == null) return false;
      if (!compiled.matcher(calleeSignature).find()) return false;
      if (bareClassName == null) return true;
      return bareClassName.equals(receiverTypeOf(calleeSignature));
    }

    /**
     * The receiver type of a callee signature, or {@code null} when it has none.
     *
     * <p>The signature shape is {@code Type.method/arity}: the type is what remains after dropping
     * the trailing method name and the arity.
     */
    private static String receiverTypeOf(String calleeSignature) {
      int slash = calleeSignature.lastIndexOf('/');
      String typeAndMethod = slash < 0 ? calleeSignature : calleeSignature.substring(0, slash);
      int dot = typeAndMethod.lastIndexOf('.');
      if (dot < 0) return null;
      return typeAndMethod.substring(0, dot);
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

  /**
   * The languages this family applies to.
   *
   * <p>Empty means "every language". A non-empty set restricts the family to those languages.
   *
   * <p>Why this matters. Most sinks are language-neutral: {@code java.sql.Statement#execute} is the
   * same symbol whether the call site was written in Java, Kotlin or Scala, so a shared family with
   * no language restriction is correct and cheapest. But some sinks are language-specific ({@code
   * kotlin.text.Regex}, {@code scala.sys.process.ProcessBuilder}), and some are <i>semantically</i>
   * language-specific even when the symbol name looks shared.
   *
   * <p>See {@link #appliesTo(SourceLanguage)}.
   */
  private final Set<SourceLanguage> languages;

  /**
   * The per-language pattern table.
   *
   * <p>See {@link LanguageRuleSet} for why this is separate from the {@code languages} filter.
   */
  private final LanguageRuleSet languageRules;

  /**
   * The SOURCE VARIABLES this family declares, by language.
   *
   * <p><b>Why a source that is not a call.</b> Every other way the catalogue names a source is a
   * callee signature: {@code java.io.FileInputStream.read}, {@code os.environ.get}. A PHP
   * superglobal is not a call -- {@code $_GET['user']} is a container access -- so no signature
   * exists to name, and the value arrives with no marker at all. Measured against the PHP side car,
   * the read lowers to an {@code ARRAYLOAD} whose base is the variable and whose index is a
   * constant: there is no callee to point a pattern at.
   *
   * <p>So a source family may name VARIABLES instead, scoped by language, and the engine seeds them
   * at the function entry beside the parameters. The scoping is what keeps it honest: the name
   * {@code _GET} means "the request's query string" in PHP and nothing at all anywhere else, so the
   * same name in a Java file is not a source.
   *
   * <p>The key is a language; the value is the list of variable names.
   */
  private final java.util.Map<SourceLanguage, java.util.List<String>> sourceVariables;

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
    this(name, cwe, severityLabel, enabled, defaultArgIndex, patterns, Set.of());
  }

  /**
   * Full constructor including the language restriction.
   *
   * @param languages the languages this family applies to; empty means every language
   */
  public SinkFamily(
      String name,
      String cwe,
      String severityLabel,
      boolean enabled,
      int defaultArgIndex,
      List<SinkPattern> patterns,
      Set<SourceLanguage> languages) {
    this(
        name,
        cwe,
        severityLabel,
        enabled,
        defaultArgIndex,
        patterns,
        languages,
        // The legacy constructor's patterns are the SHARED ones: they name symbols that exist once
        // and
        // are callable from every language. A family built the old way therefore has no
        // language-specific patterns, and resolves identically in every language.
        new LanguageRuleSet(patterns, java.util.Map.of()),
        java.util.Map.of());
  }

  /**
   * Full constructor including the per-language pattern table.
   *
   * <p>This is the constructor the YAML loader uses when a family declares per-language symbols.
   * The family's {@code patterns} become the SHARED patterns, and the rule set's per-language
   * entries are the symbols only that language can mention.
   *
   * @param languageRules the per-language pattern table
   */
  public SinkFamily(
      String name,
      String cwe,
      String severityLabel,
      boolean enabled,
      int defaultArgIndex,
      List<SinkPattern> patterns,
      Set<SourceLanguage> languages,
      LanguageRuleSet languageRules) {
    this(
        name,
        cwe,
        severityLabel,
        enabled,
        defaultArgIndex,
        patterns,
        languages,
        languageRules,
        java.util.Map.of());
  }

  /**
   * Full constructor including the source variables.
   *
   * <p>This is the constructor the YAML loader uses when a family names the variables that carry
   * untrusted data in a language where a source is not a call.
   *
   * @param sourceVariables the source variable names, by language
   */
  public SinkFamily(
      String name,
      String cwe,
      String severityLabel,
      boolean enabled,
      int defaultArgIndex,
      List<SinkPattern> patterns,
      Set<SourceLanguage> languages,
      LanguageRuleSet languageRules,
      java.util.Map<SourceLanguage, java.util.List<String>> sourceVariables) {
    this.name = Objects.requireNonNull(name, "name");
    this.cwe = cwe;
    this.severityLabel = severityLabel;
    this.enabled = enabled;
    this.defaultArgIndex = defaultArgIndex;
    this.patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns"));
    this.languages = languages == null ? Set.of() : Set.copyOf(languages);
    this.languageRules =
        languageRules == null
            ? new LanguageRuleSet(this.patterns, java.util.Map.of())
            : languageRules;
    this.sourceVariables =
        sourceVariables == null ? java.util.Map.of() : java.util.Map.copyOf(sourceVariables);
  }

  /**
   * The source variable names this family declares for a language.
   *
   * @param language the language of the code being analysed
   * @return the names; empty when the family declares none for that language
   */
  public java.util.List<String> sourceVariablesFor(SourceLanguage language) {
    if (language == null) return java.util.List.of();
    return sourceVariables.getOrDefault(language, java.util.List.of());
  }

  /**
   * The source variable names this family declares, by language.
   *
   * <p>The language is part of the declaration, not a filter applied to it: the name {@code _GET}
   * means the request's query string in PHP and nothing at all in a Java file.
   *
   * @return the names, by language
   */
  public java.util.Map<SourceLanguage, java.util.List<String>> sourceVariablesByLanguage() {
    return sourceVariables;
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
   * The languages this family applies to. Empty means "every language".
   *
   * @see #appliesTo(SourceLanguage)
   */
  public Set<SourceLanguage> languages() {
    return languages;
  }

  /**
   * Whether this family applies to code written in the given language.
   *
   * <p>An empty language set means "every language" -- that is the default for a family whose
   * patterns are all language-neutral, which is the common case.
   *
   * <p><b>How to read this.</b> The language set answers "could a call site in this language
   * possibly reach this sink?". It does not answer "is this sink dangerous here?". A shared sink
   * family (say {@code sql.sinks}) has no language restriction precisely because the same
   * Java-library symbol is reachable from every language.
   *
   * <p>A language-restricted family is for sinks that only exist in one language, or whose
   * <i>meaning</i> differs by language. The classic case of the second kind is a language's own
   * "read a line of user input" API: {@code kotlin.io.readLine()} and Scala's {@code
   * scala.io.StdIn.readLine()} are different symbols with different threat models, and a Java-only
   * source must not be blamed for a Kotlin call.
   */
  public boolean appliesTo(SourceLanguage language) {
    if (languages.isEmpty()) return true;
    return language != null && languages.contains(language);
  }

  /**
   * The per-language pattern table.
   *
   * @return the rule set; never null
   */
  public LanguageRuleSet languageRules() {
    return languageRules;
  }

  /**
   * The patterns that apply when analysing code in the given language.
   *
   * <p>This is the shared patterns plus the analysed language's own. A family built without a
   * language table returns exactly its shared patterns for every language, so the behaviour of
   * every existing family is unchanged.
   *
   * @param language the analysed language
   * @return the patterns to match a call site against; never null
   */
  public List<SinkPattern> patternsFor(SourceLanguage language) {
    return languageRules.patternsFor(language);
  }

  /**
   * Whether this family's callee signature matches, in the given language.
   *
   * <p>This is the language-aware counterpart of {@link #matches(String)}: it consults the
   * per-language pattern table, so a Kotlin-only symbol does not match in Java code.
   *
   * @param calleeSignature the callee signature at the call site
   * @param language the analysed language
   * @return true when the call site reaches this family
   */
  public boolean matches(String calleeSignature, SourceLanguage language) {
    if (calleeSignature == null) return false;
    if (!appliesTo(language)) return false;
    // The relaxed (prefix-optional) form is a FALLBACK for a callee whose
    // receiver type the symbol solver could not resolve -- the TAC then keeps
    // the simple name (`JdbcTemplate.query/2`). It must NOT be consulted for a
    // fully qualified receiver: `java.sql.Statement.execute/1` would then match
    // the relaxed form of `java.beans.Statement.execute`, and the JDBC sink
    // would be reported as a code-execution sink.
    boolean simpleName = hasSimpleNameReceiver(calleeSignature);
    for (SinkPattern p : patternsFor(language)) {
      if (p.matches(calleeSignature)) return true;
    }
    if (!simpleName) return false;
    for (SinkPattern p : patternsFor(language)) {
      if (p.relaxed().matcher(calleeSignature).find()) return true;
    }
    return false;
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
    return matchPattern(calleeSignature, null);
  }

  /**
   * The language-aware counterpart of {@link #matchPattern(String)}.
   *
   * <p>The matching logic is identical -- strict form first, relaxed form only when the receiver
   * type is a bare simple name -- but the pattern SET is the one that applies to the analysed
   * language. A family with no per-language table resolves exactly as it did before.
   *
   * @param calleeSignature the callee signature at the call site
   * @param language the analysed language; {@code null} means "unknown", which gets the shared
   *     patterns
   * @return the matching pattern, or {@code null}
   */
  public SinkPattern matchPattern(String calleeSignature, SourceLanguage language) {
    if (!enabled) return null;
    if (calleeSignature == null) return null;
    // A NULL language means "unknown". The family must still be considered:
    // an unknown language is not a reason to drop every family, because that
    // would turn "we could not tell what this code is" into "there is nothing
    // here". The language restriction is a filter for a KNOWN language; for an
    // unknown one the family is simply matched on its shared patterns.
    if (language != null && !appliesTo(language)) return null;
    List<SinkPattern> applicable = patternsFor(language);
    boolean simpleName = hasSimpleNameReceiver(calleeSignature);
    for (SinkPattern p : applicable) {
      if (p.matches(calleeSignature)) return p;
    }
    if (!simpleName) return null;
    for (SinkPattern p : applicable) {
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
   * Resolve the effective user-arg index for the given callee signature, in the given language.
   *
   * @param calleeSignature the callee signature at the call site
   * @param language the analysed language
   * @return the matching pattern's explicit argIndex, the family default if no pattern has one, or
   *     {@code -1} if no pattern matches
   */
  public int argIndexFor(String calleeSignature, SourceLanguage language) {
    SinkPattern p = matchPattern(calleeSignature, language);
    if (p == null) return -1;
    return p.hasExplicitArgIndex() ? p.argIndex() : defaultArgIndex;
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
   * EVERY argument position that can carry the taint for the given callee signature.
   *
   * <p>This is the multi-position counterpart of {@link #argIndexFor(String, SourceLanguage)}. A
   * sink symbol can have the taint at more than one argument position -- Spring's {@code
   * JdbcTemplate.queryForList} has the SQL at index 0 in one overload and the varargs at index 1 in
   * another -- and a single index would silently drop one of them.
   *
   * <p>The positions come from EVERY pattern of this family that matches the callee, not just the
   * first one. That is the point: the two patterns for {@code JdbcTemplate.queryForList} carry
   * different indices, and taking only the first one is what broke the framework sinks.
   *
   * <p>A position of {@code -1} means "the family default" and is resolved to {@link
   * #defaultArgIndex()} here, so the returned array never contains {@code -1}. The receiver
   * sentinel ({@code -2}) is returned as-is: the caller has to check the receiver separately,
   * because the receiver is not in the argument list.
   *
   * @param calleeSignature the callee signature at the call site
   * @param language the analysed language
   * @return the candidate positions, in the order they were found; empty when nothing matches
   */
  public int[] argIndexesFor(String calleeSignature, SourceLanguage language) {
    if (calleeSignature == null) return new int[0];
    java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
    boolean simpleName = hasSimpleNameReceiver(calleeSignature);
    for (SinkPattern p : patternsFor(language)) {
      boolean matched = p.matches(calleeSignature);
      if (!matched && simpleName) matched = p.relaxed().matcher(calleeSignature).find();
      if (!matched) continue;
      for (int idx : p.argIndexes()) {
        int resolved = idx == -1 ? defaultArgIndex : idx;
        if (!out.contains(resolved)) out.add(resolved);
      }
    }
    int[] arr = new int[out.size()];
    for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
    return arr;
  }

  /**
   * The language-neutral counterpart of {@link #argIndexesFor(String, SourceLanguage)}.
   *
   * @param calleeSignature the callee signature at the call site
   * @return the candidate positions; empty when nothing matches
   */
  public int[] argIndexesFor(String calleeSignature) {
    return argIndexesFor(calleeSignature, null);
  }

  /**
   * Returns the CWE family for a finding that flows into this sink. {@code null} if the family is
   * not CWE-tagged (e.g. taint sources).
   */
  public String cweOrNull() {
    return cwe;
  }
}

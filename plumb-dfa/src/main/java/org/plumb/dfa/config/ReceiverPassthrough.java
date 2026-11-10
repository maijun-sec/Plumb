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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The rules that say the RECEIVER's taint reaches the RESULT.
 *
 * <p><b>What this table is for.</b> When the engine has no summary for a callee, it cannot know
 * that the callee hands the receiver's contents back to the caller. Without a table, the result of
 * such a call is clean and the taint stops at the call -- which is how a finding goes missing.
 * Applied unconditionally, the opposite happens: every method would pass its receiver's taint on,
 * and every result would be tainted where it is not. The table is what makes the difference
 * reviewable: each entry names the callees whose receiver really does reach the result.
 *
 * <p><b>Why a rule names the LANGUAGE it is for.</b> These entries name LIBRARY symbols, and a
 * library symbol belongs to one language: {@code javax.servlet.http.HttpServletRequest} is a Java
 * type, {@code System.String} is a C# one, {@code net/http} is a Go package. The patterns cannot
 * say so themselves, because the front end may emit a callee signature in short form when the
 * symbol solver could not resolve the fully qualified name -- {@code
 * HttpServletRequest.getParameter/1} rather than {@code
 * javax.servlet.http.HttpServletRequest.getParameter/1} -- so the pattern has to accept a package
 * prefix of any length, including none.
 *
 * <p>That is the whole reason the language has to be declared beside the pattern rather than inside
 * it. A pattern that accepts a prefix of zero length will match a call whose receiver happens to be
 * called {@code String}, {@code Map} or {@code Path} -- in ANY language. So a JDK rule that is not
 * scoped to the languages that can reach the JDK gets applied to code that has no JDK at all, and
 * the receiver's taint flows into a result it never reached. The scope is what keeps the rule on
 * the code it was written for.
 *
 * @see org.plumb.dfa.taint.TaintedCheckerBase
 */
public final class ReceiverPassthrough {

  /**
   * One rule: the callee signatures it matches, and the languages it is for.
   *
   * <p>An empty language set means "every language", which is what a family that declares no
   * restriction gets. A rule that names languages applies to those languages only.
   */
  public static final class Rule {

    private final Pattern pattern;

    private final Set<SourceLanguage> languages;

    /**
     * Builds a rule that applies to every language.
     *
     * @param pattern the callee signatures the rule matches
     */
    public Rule(Pattern pattern) {
      this(pattern, Set.of());
    }

    /**
     * Builds a rule.
     *
     * @param pattern the callee signatures the rule matches
     * @param languages the languages the rule is for; empty means every language
     */
    public Rule(Pattern pattern, Set<SourceLanguage> languages) {
      this.pattern = Objects.requireNonNull(pattern, "pattern");
      this.languages =
          languages == null
              ? Set.of()
              : Collections.unmodifiableSet(new LinkedHashSet<>(languages));
    }

    /** The callee-signature pattern. */
    public Pattern pattern() {
      return pattern;
    }

    /** The languages the rule is for; empty means every language. */
    public Set<SourceLanguage> languages() {
      return languages;
    }

    /**
     * Whether the rule applies to code written in the given language.
     *
     * <p><b>What an unknown language answers.</b> A rule that names languages does not apply to an
     * unknown one. The alternative -- applying a JDK rule to code whose language the engine could
     * not determine -- is how a rule written for one language ends up firing on another, and the
     * engine cannot tell the two apart afterwards.
     *
     * @param language the analysed language; null means unknown
     * @return true when the rule applies
     */
    public boolean appliesTo(SourceLanguage language) {
      if (languages.isEmpty()) return true;
      return language != null && languages.contains(language);
    }
  }

  private final List<Rule> rules;

  /**
   * Builds the table.
   *
   * @param rules the rules
   */
  public ReceiverPassthrough(List<Rule> rules) {
    this.rules = rules == null ? List.of() : List.copyOf(rules);
  }

  /** The table with no rules: nothing passes through. */
  public static ReceiverPassthrough empty() {
    return EMPTY;
  }

  private static final ReceiverPassthrough EMPTY = new ReceiverPassthrough(List.of());

  /**
   * The table of a flat list of patterns, every one of them for every language.
   *
   * <p>This is the shape a caller that has patterns but no language scope can hand over. It is also
   * what the flattened view of a scoped table degrades to, so a caller that cannot carry the scope
   * does not silently lose the rules -- it loses the SCOPE, and says so by asking for the
   * scope-free view.
   *
   * @param patterns the patterns
   * @return the table
   */
  public static ReceiverPassthrough of(List<Pattern> patterns) {
    if (patterns == null || patterns.isEmpty()) return EMPTY;
    List<Rule> rules = new ArrayList<>(patterns.size());
    for (Pattern pattern : patterns) {
      rules.add(new Rule(pattern));
    }
    return new ReceiverPassthrough(rules);
  }

  /**
   * Whether a callee reaches the result with its receiver's taint, in the given language.
   *
   * <p>The callee signature is compared in its DOT form: a legacy JVM signature spells the owner
   * separator as {@code #}, and the rule patterns are written in the dot form.
   *
   * @param calleeSig the callee's signature
   * @param language the analysed language; null means unknown
   * @return true when a rule that applies to that language matches
   */
  public boolean matches(String calleeSig, SourceLanguage language) {
    if (calleeSig == null || calleeSig.isEmpty() || rules.isEmpty()) {
      return false;
    }
    String normalized = calleeSig.replace('#', '.');
    for (Rule rule : rules) {
      if (!rule.appliesTo(language)) continue;
      if (rule.pattern().matcher(normalized).find()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a callee reaches the result with its receiver's taint, in ANY language.
   *
   * <p>This is the scope-free view of the table: it says what the table CONTAINS, not what applies
   * to the code being analysed. The engine uses {@link #matches(String, SourceLanguage)}.
   *
   * @param calleeSig the callee's signature
   * @return true when any rule matches
   */
  public boolean matchesAnyLanguage(String calleeSig) {
    if (calleeSig == null || calleeSig.isEmpty() || rules.isEmpty()) {
      return false;
    }
    String normalized = calleeSig.replace('#', '.');
    for (Rule rule : rules) {
      if (rule.pattern().matcher(normalized).find()) {
        return true;
      }
    }
    return false;
  }

  /**
   * The rules' patterns, flattened.
   *
   * <p>The flattened view loses the language scope, so it is for callers that cannot carry it.
   *
   * @return the patterns
   */
  public List<Pattern> patterns() {
    List<Pattern> out = new ArrayList<>(rules.size());
    for (Rule rule : rules) {
      out.add(rule.pattern());
    }
    return Collections.unmodifiableList(out);
  }

  /** The number of rules. */
  public int size() {
    return rules.size();
  }
}

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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The rules that apply to ONE language.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Before this type, a rule was a flat global list. Language was a <i>filter attribute</i> on a
 * rule ({@code SinkFamily.languages}, {@code TaintRule.languages}): "does this rule apply here?"
 *
 * <p>That model answers only one question, and it forces a workaround for the other one. Consider
 * CWE-1333, a user-controlled regular expression. The <b>defect class</b> is the same in every
 * language, but the <b>sink symbol</b> is not:
 *
 * <ul>
 *   <li>Java: {@code java.util.regex.Pattern#compile}
 *   <li>Kotlin: {@code kotlin.text.Regex#<init>} (and {@code java.util.regex.Pattern} too)
 *   <li>Scala: {@code scala.util.matching.Regex#<init>}
 * </ul>
 *
 * <p>With a flat rule list, expressing this needs <b>three separate family keys</b> -- and Plumb
 * really did that: {@code regex.sinks}, {@code kotlin-regex.sinks}, {@code scala-regex.sinks}. The
 * defect class was then spread across three names that a rule had to list one by one, and a reader
 * had to know that all three mean the same thing. Add a fourth language and the list grows.
 *
 * <p>This type separates the two concepts:
 *
 * <ul>
 *   <li>the <b>defect class</b> is the family key -- one name, one CWE, one severity;
 *   <li>the <b>per-language symbol table</b> says which symbols reach that defect in which
 *       language.
 * </ul>
 *
 * <p>So {@code regex.sinks} stays one family with one CWE, and the Kotlin and Scala symbols are
 * <i>entries in that family's language table</i> rather than separate families. The three keys were
 * consolidated into one when this type landed: the defect class now has ONE name, and a rule that
 * watches it covers every language.
 *
 * <h2>How resolution works</h2>
 *
 * <p>{@link #patternsFor(SourceLanguage)} returns the union of:
 *
 * <ol>
 *   <li>the family's <b>shared</b> patterns -- the ones that apply to every language (the Java
 *       library symbol is reachable from Kotlin and Scala too, because they all run on the JVM);
 *   <li>the <b>language-specific</b> patterns registered for the analysed language.
 * </ol>
 *
 * <p>A shared pattern is language-neutral <i>by construction</i>: it names a symbol that exists
 * once and is callable from every language. A language-specific pattern names a symbol that only
 * that language's code can mention.
 *
 * <h2>Directional rule</h2>
 *
 * <p>A language table says "this symbol is reachable in this language". It never says "this
 * language is safe". A language with no entry in the table simply gets the shared patterns, which
 * is the correct and conservative answer -- it is not a statement about the code.
 */
public final class LanguageRuleSet {

  /** The shared patterns of a family: the ones that apply to every language. */
  private final List<SinkFamily.SinkPattern> sharedPatterns;

  /**
   * Per-language patterns. The key is the language; the value is that language's extra patterns.
   */
  private final Map<SourceLanguage, List<SinkFamily.SinkPattern>> byLanguage;

  /** The languages that have their own entry. Used for reporting, not for resolution. */
  private final Set<SourceLanguage> languages;

  /**
   * Creates a rule set.
   *
   * @param sharedPatterns the patterns that apply to every language
   * @param byLanguage the extra patterns per language
   */
  public LanguageRuleSet(
      List<SinkFamily.SinkPattern> sharedPatterns,
      Map<SourceLanguage, List<SinkFamily.SinkPattern>> byLanguage) {
    this.sharedPatterns = sharedPatterns == null ? List.of() : List.copyOf(sharedPatterns);
    Map<SourceLanguage, List<SinkFamily.SinkPattern>> m = new LinkedHashMap<>();
    if (byLanguage != null) {
      for (Map.Entry<SourceLanguage, List<SinkFamily.SinkPattern>> e : byLanguage.entrySet()) {
        if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
        m.put(e.getKey(), List.copyOf(e.getValue()));
      }
    }
    this.byLanguage = Collections.unmodifiableMap(m);
    this.languages = Collections.unmodifiableSet(m.keySet());
  }

  /** A rule set with nothing in it. */
  public static LanguageRuleSet empty() {
    return new LanguageRuleSet(List.of(), Map.of());
  }

  /**
   * The patterns that apply when analysing code in the given language.
   *
   * @param language the analysed language; {@code null} means "unknown", which gets the shared
   *     patterns only
   * @return the shared patterns plus this language's own, never null
   */
  public List<SinkFamily.SinkPattern> patternsFor(SourceLanguage language) {
    if (language == null) return sharedPatterns;
    List<SinkFamily.SinkPattern> own = byLanguage.get(language);
    if (own == null || own.isEmpty()) return sharedPatterns;
    if (sharedPatterns.isEmpty()) return own;
    List<SinkFamily.SinkPattern> out =
        new java.util.ArrayList<>(sharedPatterns.size() + own.size());
    out.addAll(sharedPatterns);
    out.addAll(own);
    return Collections.unmodifiableList(out);
  }

  /** The shared patterns -- those that apply to every language. */
  public List<SinkFamily.SinkPattern> sharedPatterns() {
    return sharedPatterns;
  }

  /** The patterns registered for one language, if any. */
  public Optional<List<SinkFamily.SinkPattern>> patternsForExactly(SourceLanguage language) {
    return Optional.ofNullable(byLanguage.get(language));
  }

  /** The languages that have their own patterns. */
  public Set<SourceLanguage> languages() {
    return languages;
  }

  /** Whether this rule set has any per-language content at all. */
  public boolean isEmpty() {
    return sharedPatterns.isEmpty() && byLanguage.isEmpty();
  }

  /**
   * A rule set with the given patterns added to the given language.
   *
   * @param language the language to add to
   * @param patterns the patterns to add
   * @return a new rule set; this one is unchanged
   */
  public LanguageRuleSet with(SourceLanguage language, List<SinkFamily.SinkPattern> patterns) {
    if (language == null || patterns == null || patterns.isEmpty()) return this;
    Map<SourceLanguage, List<SinkFamily.SinkPattern>> next = new LinkedHashMap<>(byLanguage);
    List<SinkFamily.SinkPattern> existing = next.get(language);
    List<SinkFamily.SinkPattern> merged = new java.util.ArrayList<>();
    if (existing != null) merged.addAll(existing);
    merged.addAll(patterns);
    next.put(language, merged);
    return new LanguageRuleSet(sharedPatterns, next);
  }

  /**
   * A rule set whose shared patterns are replaced.
   *
   * @param patterns the new shared patterns
   * @return a new rule set; this one is unchanged
   */
  public LanguageRuleSet withShared(List<SinkFamily.SinkPattern> patterns) {
    return new LanguageRuleSet(patterns, byLanguage);
  }
}

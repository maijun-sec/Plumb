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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The languages a run analyses, and the filter that removes everything the other languages own.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Plumb can read Java, Kotlin and Scala. The rules are shared, but the SYMBOLS are not: a
 * Kotlin-only sink ({@code kotlin.io.readLine}) cannot be reached from Java code, and a rule that
 * watches it has nothing to say about a Java project.
 *
 * <p>Before this type, the language was a runtime skip: the engine loaded every rule, every sink
 * family and every pattern, and then skipped the ones that did not apply while walking the
 * instructions. That is correct but it is not what the user asked for, and it costs the whole price
 * of the thing that was asked not to be loaded:
 *
 * <ul>
 *   <li>the Kotlin frontend builds an IntelliJ application and the Scala frontend builds a compiler
 *       context -- neither is cheap, and a Java-only scan must not pay for either;
 *   <li>every pattern is compiled into a {@link java.util.regex.Pattern}, and the catalogue holds
 *       thousands of them;
 *   <li>every checker is registered, and each one walks every instruction to decide it does not
 *       apply.
 * </ul>
 *
 * <p>So the language choice is a LOAD-TIME filter, not a runtime skip. The families, the patterns,
 * the checkers and the frontends that belong to the languages that were not selected are never
 * built.
 *
 * <h2>What it is NOT</h2>
 *
 * <p>A language filter is not a safety statement. Dropping the Kotlin families from a Java scan
 * says "this scan does not look at Kotlin's symbols", not "Kotlin's symbols are safe". A finding
 * that depends on a dropped family is a finding this run did not look for.
 */
public final class LanguageFilter {

  /** The selected languages. Empty means "every language" -- the default. */
  private final Set<SourceLanguage> languages;

  private LanguageFilter(Set<SourceLanguage> languages) {
    this.languages = languages;
  }

  /** The filter that selects nothing, i.e. "every language". */
  public static LanguageFilter all() {
    return new LanguageFilter(Set.of());
  }

  /**
   * A filter that selects the given languages.
   *
   * @param languages the selected languages; empty means "every language"
   */
  public static LanguageFilter of(Set<SourceLanguage> languages) {
    if (languages == null || languages.isEmpty()) return all();
    return new LanguageFilter(Set.copyOf(languages));
  }

  /**
   * Parse a comma-separated list of language names.
   *
   * <p>Unknown names are an ERROR, not a silent skip: a typo in {@code --language kotlin} would
   * otherwise produce a scan that analyses nothing and reports nothing, which is indistinguishable
   * from a clean project.
   *
   * @param spec the comma-separated names
   * @return the filter
   * @throws IllegalArgumentException when a name is not a language Plumb knows
   */
  public static LanguageFilter parse(String spec) {
    if (spec == null || spec.isBlank()) return all();
    Set<SourceLanguage> picked = new LinkedHashSet<>();
    List<String> unknown = new ArrayList<>();
    for (String raw : spec.split(",")) {
      String name = raw.trim();
      if (name.isEmpty()) continue;
      SourceLanguage language = SourceLanguage.parse(name);
      if (language == SourceLanguage.UNKNOWN) {
        unknown.add(name);
        continue;
      }
      picked.add(language);
    }
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(
          "unknown language(s): "
              + String.join(", ", unknown)
              + ". Plumb reads "
              + knownLanguageNames()
              + ".");
    }
    if (picked.isEmpty()) return all();
    return of(picked);
  }

  /** The language names Plumb accepts, for an error message. */
  public static String knownLanguageNames() {
    List<String> names = new ArrayList<>();
    for (SourceLanguage l : SourceLanguage.values()) {
      if (l == SourceLanguage.UNKNOWN) continue;
      names.add(l.name().toLowerCase(java.util.Locale.ROOT));
    }
    return String.join(", ", names);
  }

  /** The selected languages; empty means "every language". */
  public Set<SourceLanguage> languages() {
    return languages;
  }

  /** Whether this filter selects a specific set of languages. */
  public boolean isSelective() {
    return !languages.isEmpty();
  }

  /** Whether a language is analysed by this run. */
  public boolean includes(SourceLanguage language) {
    if (languages.isEmpty()) return true;
    return language != null && languages.contains(language);
  }

  /**
   * Whether a family that declares itself applicable to {@code declared} survives the filter.
   *
   * <p>A family with no language restriction applies to every language, so it survives unless the
   * filter is selective AND the family is restricted to languages that were not selected.
   */
  public boolean keepsFamily(Set<SourceLanguage> declared) {
    if (languages.isEmpty()) return true;
    if (declared == null || declared.isEmpty()) return true;
    for (SourceLanguage l : declared) {
      if (languages.contains(l)) return true;
    }
    return false;
  }

  /**
   * The checkers that survive the filter, with the per-checker configuration narrowed to the
   * selected languages.
   *
   * <p>A checker with no language declaration survives -- the conservative default, because a
   * checker with no language-specific mechanism has nothing to be excluded from.
   *
   * @param checkers the loaded checker configuration
   * @return the surviving configuration
   */
  public CheckerRegistry filterCheckers(CheckerRegistry checkers) {
    if (checkers == null || languages.isEmpty()) return checkers;
    Map<String, CheckerConfig> kept = new LinkedHashMap<>();
    for (CheckerConfig c : checkers.all()) {
      if (!keepsRule(c.languages())) continue;
      kept.put(c.name(), c);
    }
    return new CheckerRegistry(kept);
  }

  /**
   * The families that survive the filter, with the per-language pattern tables narrowed to the
   * selected languages.
   *
   * @param families the loaded families
   * @return the surviving families
   */
  public SinkFamilyRegistry filterFamilies(SinkFamilyRegistry families) {
    if (families == null || languages.isEmpty()) return families;
    Map<String, SinkFamily> kept = new LinkedHashMap<>();
    for (SinkFamily family : families.all()) {
      if (!keepsFamily(family.languages())) continue;
      kept.put(family.name(), narrowFamily(family));
    }
    return new SinkFamilyRegistry(kept);
  }

  /**
   * A family whose per-language pattern table only holds the selected languages.
   *
   * <p>The SHARED patterns are kept: a shared pattern names a symbol that exists once and is
   * callable from every language, so it is reachable from a Java scan as well.
   */
  private SinkFamily narrowFamily(SinkFamily family) {
    LanguageRuleSet rules = family.languageRules();
    Map<SourceLanguage, List<SinkFamily.SinkPattern>> narrowed = new LinkedHashMap<>();
    for (SourceLanguage language : rules.languages()) {
      if (!languages.contains(language)) continue;
      narrowed.put(language, rules.patternsForExactly(language).orElse(List.of()));
    }
    return new SinkFamily(
        family.name(),
        family.cwe(),
        family.severityLabel(),
        family.enabled(),
        family.defaultArgIndex(),
        rules.sharedPatterns(),
        family.languages(),
        new LanguageRuleSet(rules.sharedPatterns(), narrowed));
  }

  /**
   * Whether a rule that declares itself applicable to {@code declared} survives the filter.
   *
   * <p>An empty declaration means "every language", so the rule survives. A declaration that names
   * {@code all} also means "every language" -- including a language that did not exist when the
   * declaration was written.
   */
  public boolean keepsRule(Set<String> declared) {
    if (languages.isEmpty()) return true;
    if (declared == null || declared.isEmpty()) return true;
    if (declared.contains(ConfigLoader.ALL_LANGUAGES)) return true;
    for (String name : declared) {
      SourceLanguage language = SourceLanguage.parse(name);
      if (language == SourceLanguage.UNKNOWN) continue;
      if (languages.contains(language)) return true;
    }
    return false;
  }

  /** A short description for the run log. */
  public String describe() {
    if (languages.isEmpty()) return "all languages";
    List<String> names = new ArrayList<>();
    for (SourceLanguage l : languages) {
      names.add(l.name().toLowerCase(java.util.Locale.ROOT));
    }
    java.util.Collections.sort(names);
    return String.join(", ", names);
  }
}

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The language filter is a LOAD-TIME filter, and this is what "load time" has to mean.
 *
 * <h2>Why the distinction matters</h2>
 *
 * <p>The obvious implementation of "analyse only Java" is to load everything and skip the non-Java
 * rules while walking the instructions. That is correct, and it is what the engine used to do. It
 * is also not what the user asked for, because the expensive parts all happen BEFORE the skip:
 *
 * <ul>
 *   <li>the Kotlin frontend builds an IntelliJ application;
 *   <li>the Scala frontend builds a compiler context;
 *   <li>every sink pattern is compiled into a {@link java.util.regex.Pattern};
 *   <li>every checker is registered, and each one visits every instruction to decide it does not
 *       apply.
 * </ul>
 *
 * <p>So the filter removes the families, the patterns and the checkers BEFORE they are used. These
 * tests assert that the removal happens, not that the analysis produces the same findings either
 * way -- the second property is a consequence of the first, and asserting the consequence would
 * pass even if the filter did nothing.
 */
class LanguageFilterTest {

  /** A family that only the Kotlin code can reach. */
  private static SinkFamily kotlinOnlyFamily() {
    return new SinkFamily(
        "kotlin.sinks",
        "CWE-20",
        "HIGH",
        true,
        0,
        List.of(new SinkFamily.SinkPattern("^kotlin\\.io\\.readLine/0$", 0)),
        Set.of(SourceLanguage.KOTLIN));
  }

  /**
   * A family whose patterns are language-neutral: the Java library is reachable from every JVM
   * language.
   */
  private static SinkFamily sharedFamily() {
    return new SinkFamily(
        "sql.sinks",
        "CWE-89",
        "CRITICAL",
        true,
        0,
        List.of(new SinkFamily.SinkPattern("^java\\.sql\\.Statement\\.execute/1$", 0)),
        Set.of());
  }

  /** A family with a per-language table: the shared symbol plus the language's own. */
  private static SinkFamily perLanguageFamily() {
    Map<SourceLanguage, List<SinkFamily.SinkPattern>> byLanguage = new java.util.LinkedHashMap<>();
    byLanguage.put(
        SourceLanguage.KOTLIN,
        List.of(new SinkFamily.SinkPattern("^kotlin\\.text\\.Regex\\.<init>/1$", 0)));
    byLanguage.put(
        SourceLanguage.SCALA,
        List.of(new SinkFamily.SinkPattern("^scala\\.util\\.matching\\.Regex\\.<init>/1$", 0)));
    return new SinkFamily(
        "regex.sinks",
        "CWE-1333",
        "HIGH",
        true,
        0,
        List.of(new SinkFamily.SinkPattern("^java\\.util\\.regex\\.Pattern\\.compile/1$", 0)),
        Set.of(),
        new LanguageRuleSet(
            List.of(new SinkFamily.SinkPattern("^java\\.util\\.regex\\.Pattern\\.compile/1$", 0)),
            byLanguage));
  }

  @Test
  @DisplayName("the default filter selects every language")
  void defaultSelectsEverything() {
    LanguageFilter filter = LanguageFilter.all();
    assertThat(filter.isSelective()).isFalse();
    assertThat(filter.includes(SourceLanguage.KOTLIN)).isTrue();
    assertThat(filter.includes(SourceLanguage.SCALA)).isTrue();
    assertThat(filter.includes(SourceLanguage.JAVA)).isTrue();
  }

  @Test
  @DisplayName("a typo in the language name is an error, not a silent empty scan")
  void unknownLanguageIsAnError() {
    assertThatThrownBy(() -> LanguageFilter.parse("kotlinn"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kotlinn");
    assertThatThrownBy(() -> LanguageFilter.parse("java,kotl"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kotl");
  }

  @Test
  @DisplayName("the names Plumb reads are the ones the error message lists")
  void theErrorMessageListsTheRealLanguages() {
    String names = LanguageFilter.knownLanguageNames();
    assertThat(names).contains("java").contains("kotlin").contains("scala");
  }

  @Test
  @DisplayName("a Java-only run drops the Kotlin-only family before it is registered")
  void javaOnlyDropsTheKotlinOnlyFamily() {
    SinkFamilyRegistry registry =
        new SinkFamilyRegistry(
            Map.of("kotlin.sinks", kotlinOnlyFamily(), "sql.sinks", sharedFamily()));

    LanguageFilter filter = LanguageFilter.of(Set.of(SourceLanguage.JAVA));
    SinkFamilyRegistry filtered = filter.filterFamilies(registry);

    assertThat(filtered.names())
        .as("the Kotlin-only family must not be registered in a Java-only run")
        .containsExactly("sql.sinks");
  }

  @Test
  @DisplayName("a shared family survives a Java-only run: the Java library is reachable from Java")
  void sharedFamilySurvives() {
    SinkFamilyRegistry registry =
        new SinkFamilyRegistry(
            Map.of("kotlin.sinks", kotlinOnlyFamily(), "sql.sinks", sharedFamily()));

    LanguageFilter filter = LanguageFilter.of(Set.of(SourceLanguage.JAVA));
    SinkFamilyRegistry filtered = filter.filterFamilies(registry);

    assertThat(filtered.byName("sql.sinks")).isPresent();
    assertThat(filtered.byName("sql.sinks").orElseThrow().patternsFor(SourceLanguage.JAVA))
        .as("the shared pattern must still be there")
        .hasSize(1);
  }

  @Test
  @DisplayName("a Java-only run drops the Kotlin and Scala patterns of a per-language family")
  void perLanguagePatternsAreNarrowed() {
    SinkFamilyRegistry registry =
        new SinkFamilyRegistry(Map.of("regex.sinks", perLanguageFamily()));

    LanguageFilter filter = LanguageFilter.of(Set.of(SourceLanguage.JAVA));
    SinkFamilyRegistry filtered = filter.filterFamilies(registry);

    SinkFamily family = filtered.byName("regex.sinks").orElseThrow();
    assertThat(family.patternsFor(SourceLanguage.JAVA))
        .as("only the shared pattern survives")
        .hasSize(1);
    assertThat(family.patternsFor(SourceLanguage.KOTLIN))
        .as("the Kotlin pattern must not be there any more")
        .hasSize(1);
    assertThat(family.patternsFor(SourceLanguage.KOTLIN).get(0).pattern())
        .as("and what is left is the shared one, not the Kotlin symbol")
        .isEqualTo("^java\\.util\\.regex\\.Pattern\\.compile/1$");
  }

  @Test
  @DisplayName("enabling two languages keeps both of their patterns")
  void twoLanguagesKeepBothTables() {
    SinkFamilyRegistry registry =
        new SinkFamilyRegistry(Map.of("regex.sinks", perLanguageFamily()));

    LanguageFilter filter = LanguageFilter.of(Set.of(SourceLanguage.JAVA, SourceLanguage.KOTLIN));
    SinkFamilyRegistry filtered = filter.filterFamilies(registry);

    SinkFamily family = filtered.byName("regex.sinks").orElseThrow();
    assertThat(family.patternsFor(SourceLanguage.JAVA)).hasSize(1);
    assertThat(family.patternsFor(SourceLanguage.KOTLIN)).hasSize(2);
  }

  @Test
  @DisplayName("a rule with no language declaration survives every filter")
  void anUnrestrictedRuleSurvives() {
    LanguageFilter filter = LanguageFilter.of(Set.of(SourceLanguage.JAVA));
    assertThat(filter.keepsRule(Set.of())).isTrue();
    assertThat(filter.keepsRule(null)).isTrue();
  }

  @Test
  @DisplayName("a rule that declares another language is dropped")
  void aForeignRuleIsDropped() {
    LanguageFilter filter = LanguageFilter.of(Set.of(SourceLanguage.JAVA));
    assertThat(filter.keepsRule(Set.of("kotlin"))).isFalse();
    assertThat(filter.keepsRule(Set.of("scala"))).isFalse();
    assertThat(filter.keepsRule(Set.of("java"))).isTrue();
    assertThat(filter.keepsRule(Set.of("java", "kotlin"))).isTrue();
  }

  @Test
  @DisplayName("the filter does nothing when no language was selected")
  void theFilterIsInertWhenNothingWasSelected() {
    SinkFamilyRegistry registry =
        new SinkFamilyRegistry(
            Map.of("kotlin.sinks", kotlinOnlyFamily(), "sql.sinks", sharedFamily()));
    LanguageFilter filter = LanguageFilter.all();
    assertThat(filter.filterFamilies(registry).names())
        .containsExactlyInAnyOrder("kotlin.sinks", "sql.sinks");
  }
}

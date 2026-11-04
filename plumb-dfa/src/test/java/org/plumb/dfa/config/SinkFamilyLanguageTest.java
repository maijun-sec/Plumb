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

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The language dimension of the rule catalog.
 *
 * <p>A sink family can be restricted to the languages it can actually be reached from, and a lookup
 * has to honour that restriction. These tests check both halves: the YAML is parsed as written, and
 * the lookup answers the language question.
 */
class SinkFamilyLanguageTest {

  private SinkFamilyRegistry load() {
    return ConfigLoader.loadFromClasspath().sinkFamilies();
  }

  @Test
  @DisplayName("the shipped catalog parses and every family is loadable")
  void catalogParses() {
    SinkFamilyRegistry registry = load();
    assertThat(registry.names()).isNotEmpty();
    // The families the language work added must be present.
    assertThat(registry.names()).contains("regex.sinks", "command.sinks");
    assertThat(registry.names()).contains("file-permission.sinks");
  }

  @Test
  @DisplayName("a language-specific symbol is reachable only in its own language")
  void languageSpecificSymbolIsReachableOnlyInItsLanguage() {
    SinkFamilyRegistry registry = load();

    // `kotlin.text.Regex` is a Kotlin-only sink. It is an entry in the
    // `regex.sinks` family's Kotlin table, not a family of its own -- so the
    // defect class keeps ONE name and ONE CWE.
    String kotlinRegexCall = "kotlin.text.Regex.<init>/1";
    assertThat(registry.lookupAll(kotlinRegexCall, SourceLanguage.KOTLIN))
        .as("a Kotlin call site must see the Kotlin regex symbol")
        .isNotEmpty();
    assertThat(registry.lookupAll(kotlinRegexCall, SourceLanguage.JAVA))
        .as("a Java call site must not see the Kotlin regex symbol")
        .isEmpty();
    assertThat(registry.lookupAll(kotlinRegexCall, SourceLanguage.SCALA))
        .as("a Scala call site must not see the Kotlin regex symbol")
        .isEmpty();
  }

  @Test
  @DisplayName("a shared family applies to every language")
  void sharedFamilyAppliesEverywhere() {
    SinkFamilyRegistry registry = load();
    SinkFamily sql = registry.byName("sql.sinks").orElseThrow();
    assertThat(sql.languages()).isEmpty();
    assertThat(sql.appliesTo(SourceLanguage.JAVA)).isTrue();
    assertThat(sql.appliesTo(SourceLanguage.KOTLIN)).isTrue();
    assertThat(sql.appliesTo(SourceLanguage.SCALA)).isTrue();
  }

  @Test
  @DisplayName("the language-aware lookup excludes a family that does not apply")
  void languageAwareLookupRespectsTheRestriction() {
    SinkFamilyRegistry registry = load();

    // `kotlin.text.Regex` is a Kotlin-only sink.
    String kotlinRegexCall = "kotlin.text.Regex.<init>/1";
    assertThat(registry.lookupAll(kotlinRegexCall, SourceLanguage.KOTLIN))
        .as("a Kotlin call site must see the Kotlin regex family")
        .isNotEmpty();
    assertThat(registry.lookupAll(kotlinRegexCall, SourceLanguage.JAVA))
        .as("a Java call site must not see the Kotlin regex family")
        .isEmpty();
  }

  @Test
  @DisplayName("the shared regex family is reachable from every language")
  void sharedRegexFamilyIsReachableEverywhere() {
    SinkFamilyRegistry registry = load();
    String patternCompile = "java.util.regex.Pattern.compile/1";
    for (SourceLanguage lang :
        List.of(SourceLanguage.JAVA, SourceLanguage.KOTLIN, SourceLanguage.SCALA)) {
      assertThat(registry.lookupAll(patternCompile, lang))
          .as("java.util.regex.Pattern is reachable from %s", lang)
          .isNotEmpty();
    }
  }

  @Test
  @DisplayName("a null language falls back to the unrestricted lookup")
  void nullLanguageFallsBack() {
    SinkFamilyRegistry registry = load();
    // A null language means "unknown". The SHARED patterns still apply -- the
    // family must not be dropped just because the language could not be
    // determined. The per-language table does not apply, because a symbol
    // that only Kotlin code can mention is not known to be reachable here.
    assertThat(registry.lookupAll("java.util.regex.Pattern.compile/1", null))
        .as("an unknown language must not silently drop every family")
        .isNotEmpty();
  }

  @Test
  @DisplayName("the CWE-732 family is shared, not language-restricted")
  void filePermissionFamilyIsShared() {
    SinkFamilyRegistry registry = load();
    SinkFamily perms = registry.byName("file-permission.sinks").orElseThrow();
    // The family carries every CWE the defect class is named by: the
    // hand-written file said CWE-732, and the converted rules also mark it as
    // CWE-264 (permissions on a file that should not be writable).
    assertThat(perms.cweOrNull()).contains("CWE-732");
    for (SourceLanguage lang :
        List.of(SourceLanguage.JAVA, SourceLanguage.KOTLIN, SourceLanguage.SCALA)) {
      assertThat(perms.appliesTo(lang)).isTrue();
    }
  }

  @Test
  @DisplayName("every language-restricted family names a known language")
  void restrictedFamiliesNameKnownLanguages() {
    SinkFamilyRegistry registry = load();
    for (SinkFamily f : registry.all()) {
      if (f.languages().isEmpty()) continue;
      assertThat(f.languages()).doesNotContain(SourceLanguage.UNKNOWN);
      assertThat(f.languages()).allMatch(l -> l != SourceLanguage.UNKNOWN);
    }
  }

  @Test
  @DisplayName("a family is reachable by name")
  void lookupByNameStillWorks() {
    SinkFamilyRegistry registry = load();
    Optional<SinkFamily> found = registry.byName("regex.sinks");
    assertThat(found).isPresent();
    assertThat(found.get().languageRules().languages())
        .as("the regex defect class carries a Kotlin and a Scala table")
        .contains(SourceLanguage.KOTLIN, SourceLanguage.SCALA);
  }
}

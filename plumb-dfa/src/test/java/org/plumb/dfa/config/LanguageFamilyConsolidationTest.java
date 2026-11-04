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
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;

/**
 * A defect class is ONE family, and the languages differ in the SYMBOL TABLE.
 *
 * <p>Plumb used to spread a defect class over one family per language: {@code regex.sinks}, {@code
 * kotlin-regex.sinks}, {@code scala-regex.sinks}. A rule then had to name all three, and a reader
 * had to know that all three meant the same thing. The per-language table fixed that: the defect
 * class is one family with one CWE, and the language-specific symbols are entries in its table.
 *
 * <p>These tests pin the consolidation, because the two models are behaviourally close and the
 * difference only shows in how a rule is written.
 */
class LanguageFamilyConsolidationTest {

  private SinkFamilyRegistry load() {
    return ConfigLoader.loadFromClasspath().sinkFamilies();
  }

  private Set<String> familiesFor(String calleeSignature, SourceLanguage language) {
    return load().lookupAll(calleeSignature, language).stream()
        .map(SinkFamily::name)
        .collect(Collectors.toSet());
  }

  @Test
  @DisplayName("the per-language family keys are gone")
  void perLanguageFamilyKeysAreGone() {
    Set<String> names = load().names();
    assertThat(names)
        .as("a defect class has ONE key; the language lives in the symbol table")
        .doesNotContain(
            "kotlin-regex.sinks",
            "scala-regex.sinks",
            "kotlin-process.sinks",
            "scala-process.sinks",
            "taint.sources.kotlin.console",
            "taint.sources.scala.console",
            "taint.sources.kotlin.env",
            "taint.sources.scala.env");
  }

  @Test
  @DisplayName("the regex defect class is one family with a Kotlin and a Scala table")
  void regexDefectClassIsOneFamily() {
    SinkFamily regex = load().byName("regex.sinks").orElseThrow();

    assertThat(regex.languageRules().languages())
        .as("the Kotlin and Scala symbols are entries in the family's table")
        .contains(SourceLanguage.KOTLIN, SourceLanguage.SCALA);
    assertThat(regex.cweOrNull()).as("one defect class, one CWE").contains("CWE-1333");
  }

  @Test
  @DisplayName("the command defect class is one family with a Kotlin and a Scala table")
  void commandDefectClassIsOneFamily() {
    SinkFamily command = load().byName("command.sinks").orElseThrow();

    assertThat(command.languageRules().languages())
        .as("kotlin.process and scala.sys.process are entries in the family's table")
        .contains(SourceLanguage.KOTLIN, SourceLanguage.SCALA);
  }

  @Test
  @DisplayName("the console and environment source families carry their language tables")
  void sourceFamiliesCarryTheirLanguageTables() {
    SinkFamilyRegistry registry = load();

    assertThat(registry.byName("taint.sources.console").orElseThrow().languageRules().languages())
        .as("kotlin.io.readLine and scala.io.StdIn.readLine are source symbols")
        .contains(SourceLanguage.KOTLIN, SourceLanguage.SCALA);
    assertThat(registry.byName("taint.sources.env").orElseThrow().languageRules().languages())
        .as("kotlin.system.getenv and scala.sys.package.getenv are source symbols")
        .contains(SourceLanguage.KOTLIN, SourceLanguage.SCALA);
  }

  @Test
  @DisplayName("each language's regex symbol reaches the SAME family")
  void eachLanguageReachesTheSameFamily() {
    // The point of the consolidation: the Java, Kotlin and Scala regex sinks
    // all land in ONE family, so one rule covers them and the finding carries
    // the same CWE everywhere.
    assertThat(familiesFor("java.util.regex.Pattern.compile/1", SourceLanguage.JAVA))
        .contains("regex.sinks");
    assertThat(familiesFor("kotlin.text.Regex.<init>/1", SourceLanguage.KOTLIN))
        .contains("regex.sinks");
    assertThat(familiesFor("scala.util.matching.Regex.<init>/1", SourceLanguage.SCALA))
        .contains("regex.sinks");
  }

  @Test
  @DisplayName("a language's symbol is invisible to the other languages")
  void aLanguageSymbolIsInvisibleToTheOthers() {
    assertThat(familiesFor("kotlin.text.Regex.<init>/1", SourceLanguage.JAVA)).isEmpty();
    assertThat(familiesFor("kotlin.text.Regex.<init>/1", SourceLanguage.SCALA)).isEmpty();
    assertThat(familiesFor("scala.util.matching.Regex.<init>/1", SourceLanguage.JAVA)).isEmpty();
    assertThat(familiesFor("scala.util.matching.Regex.<init>/1", SourceLanguage.KOTLIN)).isEmpty();
  }

  @Test
  @DisplayName("the shared Java symbols stay reachable from every language")
  void sharedSymbolsStayReachableEverywhere() {
    for (SourceLanguage lang :
        List.of(SourceLanguage.JAVA, SourceLanguage.KOTLIN, SourceLanguage.SCALA)) {
      assertThat(familiesFor("java.util.regex.Pattern.compile/1", lang))
          .as("java.util.regex.Pattern is reachable from %s", lang)
          .contains("regex.sinks");
      assertThat(familiesFor("java.lang.Runtime.exec/1", lang))
          .as("java.lang.Runtime#exec is reachable from %s", lang)
          .contains("command.sinks");
    }
  }
}

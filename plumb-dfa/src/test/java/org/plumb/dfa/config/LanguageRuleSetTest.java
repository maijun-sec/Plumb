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
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The per-language rule layer: ONE defect class, SEVERAL language symbol tables.
 *
 * <p><b>The distinction this type exists for.</b> A sink family used to answer one question --
 * "does this callee reach this defect class?" -- with a flat pattern list and a language FILTER
 * beside it. That model cannot express the common case:
 *
 * <blockquote>
 * CWE-1333 is a user-controlled regular expression. The defect class is the same everywhere. The
 * SINK SYMBOL is not: {@code java.util.regex.Pattern#compile} in Java, {@code
 * kotlin.text.Regex#<init>} in Kotlin, {@code scala.util.matching.Regex#<init>} in Scala.
 * </blockquote>
 *
 * <p>With a flat list, expressing this needed three separate family keys -- and Plumb really did
 * that ({@code regex.sinks}, {@code kotlin-regex.sinks}, {@code scala-regex.sinks}), so the defect
 * class was spread over three names that a rule author had to list one by one.
 *
 * <p>The per-language table keeps the defect class as ONE name with ONE CWE, and says which symbols
 * reach it in which language. The three keys were consolidated into one: {@code regex.sinks} now
 * carries the Java symbols as shared patterns and the Kotlin and Scala ones in its language table.
 *
 * <p>These tests pin both halves: the shared patterns still apply everywhere, and a language's own
 * patterns apply in that language and NOWHERE ELSE.
 */
class LanguageRuleSetTest {

  private SinkFamilyRegistry load() {
    return ConfigLoader.loadFromClasspath().sinkFamilies();
  }

  @Test
  @DisplayName("a family with no language table resolves identically in every language")
  void aFamilyWithoutALanguageTableIsLanguageNeutral() {
    // `code-execution.sinks` has no per-language table: it names only Java
    // library classes, so its pattern set is the same everywhere.
    SinkFamily codeExec = load().byName("code-execution.sinks").orElseThrow();

    assertThat(codeExec.languageRules().languages()).isEmpty();
    assertThat(codeExec.patternsFor(SourceLanguage.JAVA))
        .isEqualTo(codeExec.patternsFor(SourceLanguage.KOTLIN));
    assertThat(codeExec.patternsFor(SourceLanguage.KOTLIN))
        .isEqualTo(codeExec.patternsFor(SourceLanguage.SCALA));
  }

  @Test
  @DisplayName("a shared pattern matches in every language")
  void aSharedPatternMatchesEverywhere() {
    SinkFamily sql = load().byName("sql.sinks").orElseThrow();

    // java.sql.Statement is a Java LIBRARY class, so it is on the JVM for
    // Kotlin and Scala too -- and the call site can be written in any of them.
    for (SourceLanguage lang :
        List.of(SourceLanguage.JAVA, SourceLanguage.KOTLIN, SourceLanguage.SCALA)) {
      assertThat(sql.matches("java.sql.Statement.execute/1", lang))
          .as("java.sql.Statement.execute must be a sink in %s", lang)
          .isTrue();
    }
  }

  @Test
  @DisplayName("a language's own pattern applies in that language only")
  void aLanguagePatternAppliesOnlyInItsLanguage() {
    SinkFamily registry = load().byName("regex.sinks").orElseThrow();

    // The converted rules put `kotlin.text.Regex` into the Kotlin
    // table. It must match Kotlin code...
    assertThat(registry.matches("kotlin.text.Regex.<init>/1", SourceLanguage.KOTLIN)).isTrue();
    // ...and NOT Java code, and NOT Scala code.
    assertThat(registry.matches("kotlin.text.Regex.<init>/1", SourceLanguage.JAVA)).isFalse();
    assertThat(registry.matches("kotlin.text.Regex.<init>/1", SourceLanguage.SCALA)).isFalse();
  }

  @Test
  @DisplayName("the language table holds the converted per-language symbols")
  void theLanguageTableHoldsTheConvertedSymbols() {
    SinkFamily regex = load().byName("regex.sinks").orElseThrow();

    assertThat(regex.languageRules().languages())
        .as("the converted rules contribute a Kotlin and a Scala table")
        .contains(SourceLanguage.KOTLIN, SourceLanguage.SCALA);
  }

  @Test
  @DisplayName("the language table is a UNION with the shared patterns, not a replacement")
  void theLanguageTableAddsToTheSharedPatterns() {
    SinkFamily regex = load().byName("regex.sinks").orElseThrow();

    long shared = regex.patternsFor(SourceLanguage.JAVA).size();
    long kotlin = regex.patternsFor(SourceLanguage.KOTLIN).size();

    assertThat(kotlin).as("the Kotlin table adds to the shared patterns").isGreaterThan(shared);
    // The shared patterns are still there in the Kotlin view.
    assertThat(regex.matches("java.util.regex.Pattern.compile/1", SourceLanguage.KOTLIN))
        .as("a Java library class is reachable from Kotlin too")
        .isTrue();
  }

  @Test
  @DisplayName("the argument index is resolved per language")
  void theArgumentIndexIsResolvedPerLanguage() {
    SinkFamily registry = load().byName("sql.sinks").orElseThrow();

    // The converted rules give an explicit arg_index per API:
    // java.sql.Statement#execute takes the command at index 0.
    assertThat(registry.argIndexFor("java.sql.Statement.execute/1", SourceLanguage.JAVA))
        .isEqualTo(0);
    // ...and java.sql.PreparedStatement#setObject takes the VALUE at index 1
    // (index 0 is the column index).
    //
    // NOTE the family. The bound-parameter position belongs to
    // `database-access.sinks` (CWE-566), not to `sql.sinks` (CWE-89): a bound
    // parameter cannot change the statement, so it is not a SQL injection. The
    // SQL family keeps the SQL-text semantics.
    SinkFamily db = load().byName("database-access.sinks").orElseThrow();
    assertThat(db.argIndexFor("java.sql.PreparedStatement.setObject/2", SourceLanguage.JAVA))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("the registry lookup is language-aware for a per-language symbol")
  void theRegistryLookupIsLanguageAware() {
    SinkFamilyRegistry registry = load();

    assertThat(registry.lookupAll("kotlin.text.Regex.<init>/1", SourceLanguage.KOTLIN))
        .as("the Kotlin sink is found in Kotlin")
        .isNotEmpty();
    assertThat(registry.lookupAll("kotlin.text.Regex.<init>/1", SourceLanguage.JAVA))
        .as("the Kotlin sink is NOT found in Java")
        .isEmpty();
  }

  @Test
  @DisplayName("the converted families are present and carry a CWE")
  void theConvertedFamiliesArePresent() {
    SinkFamilyRegistry registry = load();

    // Families that came from a converted rule pack.
    assertThat(registry.names()).contains("ssrf.sinks", "resource-injection.sinks");
    // Families that came from the other upstream model library.
    assertThat(registry.names()).contains("android-implicit-intent.sinks");
    // And one that only exists because of the conversion.
    assertThat(registry.names()).contains("formula-injection.sinks");

    for (String key : List.of("ssrf.sinks", "android-implicit-intent.sinks")) {
      SinkFamily f = registry.byName(key).orElseThrow();
      assertThat(f.cwe()).as("%s must carry a CWE", key).isNotNull();
    }
  }

  @Test
  @DisplayName("an unknown language name in a by_language table is rejected")
  void anUnknownLanguageNameIsRejected() {
    // The loader THROWS on an unknown language name rather than skipping it.
    // A silently-skipped entry would make the rule look configured while
    // being unable to fire -- the same failure mode as a typo'd family key.
    Map<String, Object> spec = Map.of("by_language", Map.of("cobol", List.of("'^x'")));
    Map<String, Object> yaml = Map.of("families", Map.of("probe.sinks", spec));

    assertThat(
            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> ConfigLoader.parseSinkFamiliesForTest(yaml)))
        .hasMessageContaining("cobol");
  }
}

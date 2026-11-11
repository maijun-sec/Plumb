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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;
import org.yaml.snakeyaml.Yaml;

/**
 * The catalogue's holdings, as the ENGINE holds them.
 *
 * <p><b>Why this test exists.</b> The generated documents quote the size of the rule catalogue: how
 * many source patterns, how many sink patterns, how many families. Those numbers were measured by a
 * line-based reader of the YAML, and such a reader cannot tell a pattern from a source VARIABLE --
 * both are list items under a key, and only the key says which one it is. So the documents counted
 * the ten source variables as source patterns, and they classified the families by their NAME,
 * which found six of the eleven source families and counted the other five as sinks.
 *
 * <p>What made it survive is the shape of the check. The generator and the document agreed with
 * each other, so the gate that compares a document with its generator stayed green: the number was
 * wrong in the generator and in the document together, and nothing compared it with the thing that
 * actually holds the patterns.
 *
 * <p><b>What this test pins.</b> The engine's own holdings, against the catalogue the engine is
 * given. It quotes no number: it says the engine holds what the file declares, so the catalogue can
 * grow or shrink without the test needing an edit, and a pattern the engine DROPS makes it red. The
 * engine drops three ways, and each one is a silent loss:
 *
 * <ul>
 *   <li>a bucket naming a language the engine does not know is skipped entirely;
 *   <li>a bucket naming a language outside the family's own {@code languages:} list is skipped,
 *       because the family itself does not apply there;
 *   <li>a bucket that names the same language as another bucket OVERWRITES the first, so the
 *       patterns of the earlier one are gone.
 * </ul>
 *
 * <p>A pattern that does not compile is dropped too, and no test can see it: the text is still in
 * the file, so the file and the engine would have to disagree about the TEXT, and the text is what
 * this test compares.
 */
@DisplayName("the engine holds exactly what the catalogue declares")
class SinkFamilyHoldingsTest {

  private static final SinkFamilyRegistry REGISTRY =
      ConfigLoader.loadFromClasspath().sinkFamilies();

  /** The catalogue, read as the file declares it: the family name to its raw block. */
  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Object>> catalogue() {
    try (InputStream in = SinkFamilyHoldingsTest.class.getResourceAsStream("/sink-families.yaml")) {
      assertThat(in).as("the catalogue is on the classpath").isNotNull();
      Object loaded = new Yaml().load(in);
      assertThat(loaded).as("the catalogue parses").isInstanceOf(Map.class);
      Object families = ((Map<String, Object>) loaded).get("families");
      assertThat(families).as("the catalogue has a families block").isInstanceOf(Map.class);
      return (Map<String, Map<String, Object>>) families;
    } catch (IOException e) {
      throw new AssertionError("the catalogue could not be read", e);
    }
  }

  /** The pattern texts a family block declares, the shared ones and the per-language ones. */
  @SuppressWarnings("unchecked")
  private static Set<String> declaredPatterns(Map<String, Object> block) {
    Set<String> out = new LinkedHashSet<>();
    for (Object item : (List<Object>) block.getOrDefault("patterns", List.of())) {
      out.add(patternText(item));
    }
    Object byLanguage = block.get("by_language");
    if (byLanguage instanceof Map<?, ?> buckets) {
      for (Object bucket : buckets.values()) {
        // A bucket with no value declares no pattern. The loader skips it, and
        // so does the declaration: the engine holds nothing for it either.
        if (!(bucket instanceof List<?> items)) continue;
        for (Object item : items) {
          out.add(patternText(item));
        }
      }
    }
    return out;
  }

  /** A pattern entry is either a bare string or a map that carries the pattern and an index. */
  private static String patternText(Object item) {
    if (item instanceof String text) return text;
    if (item instanceof Map<?, ?> map) {
      Object text = map.get("pattern");
      if (text instanceof String s) return s;
    }
    throw new AssertionError("a pattern entry is neither a string nor a pattern map: " + item);
  }

  /** The pattern texts the engine holds for a family. */
  private static Set<String> heldPatterns(SinkFamily family) {
    Set<String> out = new LinkedHashSet<>();
    for (SinkFamily.SinkPattern pattern : family.patterns()) {
      out.add(pattern.pattern());
    }
    for (SourceLanguage language : family.languageRules().languages()) {
      for (SinkFamily.SinkPattern pattern :
          family.languageRules().patternsForExactly(language).orElse(List.of())) {
        out.add(pattern.pattern());
      }
    }
    return out;
  }

  /** The source variables a family block declares, by the language each name belongs to. */
  @SuppressWarnings("unchecked")
  private static Map<SourceLanguage, List<String>> declaredSourceVariables(
      Map<String, Object> block) {
    Map<SourceLanguage, List<String>> out = new LinkedHashMap<>();
    Object raw = block.get("source_variables");
    if (!(raw instanceof Map<?, ?> buckets)) return out;
    for (Map.Entry<?, ?> entry : buckets.entrySet()) {
      List<String> names = new ArrayList<>();
      if (entry.getValue() instanceof List<?> list) {
        for (Object item : list) {
          // The loader trims the names and drops the blank ones, so the
          // declaration is read here the same way it is read there.
          if (!(item instanceof String text)) continue;
          String value = text.trim();
          if (!value.isEmpty()) names.add(value);
        }
      }
      if (names.isEmpty()) continue;
      for (String part : String.valueOf(entry.getKey()).split(",")) {
        String name = part.trim();
        if (name.isEmpty()) continue;
        SourceLanguage language = SourceLanguage.parse(name);
        List<String> forLanguage = out.computeIfAbsent(language, k -> new ArrayList<>());
        // The loader seeds a name once, even when two families declare it.
        for (String value : names) {
          if (!forLanguage.contains(value)) forLanguage.add(value);
        }
      }
    }
    return out;
  }

  /** The languages the family covers; empty means every language. */
  @SuppressWarnings("unchecked")
  private static Set<SourceLanguage> declaredLanguages(Map<String, Object> block) {
    Set<SourceLanguage> out = new LinkedHashSet<>();
    Object raw = block.get("languages");
    if (!(raw instanceof List<?> list)) return out;
    for (Object item : list) {
      out.add(SourceLanguage.parse(String.valueOf(item)));
    }
    return out;
  }

  @Test
  @DisplayName("every family holds exactly the patterns the catalogue declares")
  void everyFamilyHoldsExactlyThePatternsTheCatalogueDeclares() {
    Map<String, Map<String, Object>> families = catalogue();
    assertThat(families).as("the catalogue declares families").isNotEmpty();
    for (Map.Entry<String, Map<String, Object>> entry : families.entrySet()) {
      String name = entry.getKey();
      SinkFamily family = REGISTRY.byName(name).orElse(null);
      assertThat(family).as("the engine loaded the family %s", name).isNotNull();
      assertThat(heldPatterns(family))
          .as("the patterns the engine holds for %s", name)
          .containsExactlyInAnyOrderElementsOf(declaredPatterns(entry.getValue()));
    }
  }

  @Test
  @DisplayName("every family holds exactly the source variables the catalogue declares")
  void everyFamilyHoldsExactlyTheSourceVariablesTheCatalogueDeclares() {
    Map<String, Map<String, Object>> families = catalogue();
    for (Map.Entry<String, Map<String, Object>> entry : families.entrySet()) {
      String name = entry.getKey();
      SinkFamily family = REGISTRY.byName(name).orElse(null);
      assertThat(family).as("the engine loaded the family %s", name).isNotNull();
      assertThat(family.sourceVariablesByLanguage())
          .as("the source variables the engine holds for %s", name)
          .isEqualTo(declaredSourceVariables(entry.getValue()));
    }
  }

  @Test
  @DisplayName("the source families are the ones the catalogue gives no CWE")
  void theSourceFamiliesAreTheOnesTheCatalogueGivesNoCwe() {
    Set<String> declared = new LinkedHashSet<>();
    for (Map.Entry<String, Map<String, Object>> entry : catalogue().entrySet()) {
      // A family with no CWE is a source. The catalogue writes the key with no value in
      // some families, so an absent key and a null value mean the same thing.
      if (entry.getValue().get("cwe") == null) {
        declared.add(entry.getKey());
      }
    }
    assertThat(declared).as("the catalogue declares source families").isNotEmpty();
    assertThat(REGISTRY.sourceFamilyMatcher().sourceFamilyNames())
        .as("the families the engine treats as sources")
        .containsExactlyInAnyOrderElementsOf(declared);
  }

  @Test
  @DisplayName("no bucket names a language the engine cannot use")
  void noBucketNamesALanguageTheEngineCannotUse() {
    Map<String, Map<String, Object>> families = catalogue();
    for (Map.Entry<String, Map<String, Object>> entry : families.entrySet()) {
      String name = entry.getKey();
      Set<SourceLanguage> familyLanguages = declaredLanguages(entry.getValue());
      List<String> buckets = new ArrayList<>();
      Object byLanguage = entry.getValue().get("by_language");
      if (byLanguage instanceof Map<?, ?> map) {
        for (Object key : map.keySet()) buckets.add(String.valueOf(key));
      }
      Object variables = entry.getValue().get("source_variables");
      if (variables instanceof Map<?, ?> map) {
        for (Object key : map.keySet()) buckets.add(String.valueOf(key));
      }
      for (String bucket : buckets) {
        for (String part : bucket.split(",")) {
          String languageName = part.trim();
          if (languageName.isEmpty()) continue;
          SourceLanguage language = SourceLanguage.parse(languageName);
          assertThat(language)
              .as(
                  "the bucket '%s' of the family %s names a language the engine knows",
                  bucket, name)
              .isNotEqualTo(SourceLanguage.UNKNOWN);
          assertThat(familyLanguages.isEmpty() || familyLanguages.contains(language))
              .as(
                  "the bucket '%s' of the family %s names a language the family covers",
                  bucket, name)
              .isTrue();
        }
      }
    }
  }
}

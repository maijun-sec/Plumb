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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;

/**
 * A sink pattern can name SEVERAL argument positions as carrying the taint.
 *
 * <p><b>WHY THIS EXISTS.</b> A rule pack ships several rules for one symbol, and they do not always
 * agree about WHICH argument carries the taint. The motivating case is Spring's {@code
 * JdbcTemplate}: one rule says the tainted argument is index 1 (the varargs of {@code
 * queryForList(String, Object...)}), another says index 0 (the SQL of {@code queryForList(String,
 * RowMapper)}). Both are correct -- the class has both overloads.
 *
 * <p>The old model had ONE index per pattern, and the matcher took the FIRST matching pattern. So
 * one of the two positions was silently dropped, and WHICH one was decided by list order. The
 * finding still fired; it just pointed at the wrong argument.
 *
 * <p>So a pattern carries a SET of candidate positions, and the checker reports a finding when ANY
 * of them is tainted. The single-index form is the one-element case, so nothing that worked before
 * changes.
 */
class MultiArgIndexTest {

  private static SinkFamilyRegistry parse(Object... patternEntries) {
    Map<String, Object> families =
        Map.of(
            "families",
            Map.of("t.sinks", Map.of("cwe", "CWE-89", "patterns", List.of(patternEntries))));
    return ConfigLoader.parseSinkFamiliesForTest(families);
  }

  /** A pattern entry with a single candidate position. */
  private static Map<String, Object> entry(String pattern, Object argIndex) {
    return Map.of("pattern", pattern, "arg_index", argIndex);
  }

  /** A pattern entry with several candidate positions. */
  private static Map<String, Object> entry(String pattern, int... argIndexes) {
    java.util.ArrayList<Integer> list = new java.util.ArrayList<>();
    for (int i : argIndexes) list.add(i);
    return Map.of("pattern", pattern, "arg_index", list);
  }

  @Test
  @DisplayName("a scalar arg_index is the one-element case")
  void aScalarArgIndexIsTheOneElementCase() {
    SinkFamily family = parse(entry("^A\\.b(/\\d+)?", 2)).byName("t.sinks").orElseThrow();

    assertThat(family.patterns().get(0).argIndex()).isEqualTo(2);
    assertThat(family.patterns().get(0).hasMultipleArgIndexes()).isFalse();
    assertThat(family.patterns().get(0).argIndexes()).containsExactly(2);
  }

  @Test
  @DisplayName("a list arg_index names every candidate position")
  void aListArgIndexNamesEveryCandidatePosition() {
    SinkFamily family = parse(entry("^A\\.b(/\\d+)?", 0, 1)).byName("t.sinks").orElseThrow();

    assertThat(family.patterns().get(0).hasMultipleArgIndexes()).isTrue();
    assertThat(family.patterns().get(0).argIndexes()).containsExactly(0, 1);
  }

  @Test
  @DisplayName("every candidate position is resolved for the callee")
  void everyCandidatePositionIsResolved() {
    SinkFamily family = parse(entry("^A\\.b(/\\d+)?", 0, 1)).byName("t.sinks").orElseThrow();

    assertThat(family.argIndexesFor("A.b/2")).containsExactly(0, 1);
  }

  @Test
  @DisplayName("the positions of EVERY matching pattern are resolved, not just the first")
  void thePositionsOfEveryMatchingPatternAreResolved() {
    // Two patterns for the same callee with different positions: BOTH positions
    // are checked. Taking only the first is what dropped a tainted SQL text.
    SinkFamilyRegistry registry = parse(entry("^A\\.b(/\\d+)?", 0), entry("^A\\.b(/\\d+)?", 1));
    SinkFamily family = registry.byName("t.sinks").orElseThrow();

    assertThat(family.argIndexesFor("A.b/2"))
        .as("both positions are inspected")
        .containsExactly(0, 1);
  }

  @Test
  @DisplayName("the receiver sentinel survives a multi-position pattern")
  void theReceiverSentinelSurvivesAMultiPositionPattern() {
    SinkFamily family = parse(entry("^A\\.b(/\\d+)?", -2, 0)).byName("t.sinks").orElseThrow();

    assertThat(family.argIndexesFor("A.b/2")).containsExactly(-2, 0);
  }

  @Test
  @DisplayName("an unset index falls back to the family default")
  void anUnsetIndexFallsBackToTheFamilyDefault() {
    Map<String, Object> families =
        Map.of(
            "families",
            Map.of(
                "t.sinks",
                Map.of(
                    "cwe",
                    "CWE-89",
                    "default_arg_index",
                    3,
                    "patterns",
                    List.of(Map.of("pattern", "^A\\.b(/\\d+)?")))));
    SinkFamily family =
        ConfigLoader.parseSinkFamiliesForTest(families).byName("t.sinks").orElseThrow();

    assertThat(family.argIndexesFor("A.b/2")).containsExactly(3);
  }

  @Test
  @DisplayName("the shipped catalog uses the multi-position form")
  void theShippedCatalogUsesTheMultiPositionForm() {
    // The converted rules produce these, so the loader has to read them.
    long multi =
        ConfigLoader.loadFromClasspath().sinkFamilies().all().stream()
            .flatMap(f -> f.patterns().stream())
            .filter(SinkFamily.SinkPattern::hasMultipleArgIndexes)
            .count();

    assertThat(multi)
        .as("the catalog carries patterns with more than one candidate position")
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("the list form survives a round trip through the catalog")
  void theListFormSurvivesARoundTrip() {
    SinkFamily family = parse(entry("^A\\.b(/\\d+)?", 0, 1)).byName("t.sinks").orElseThrow();

    int[] resolved = family.argIndexesFor("A.b/2", SourceLanguage.JAVA);
    assertThat(Arrays.toString(resolved)).isEqualTo("[0, 1]");
  }
}

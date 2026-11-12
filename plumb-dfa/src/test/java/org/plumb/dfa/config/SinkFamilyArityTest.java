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

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;

/**
 * No catalogue pattern may pin the arity.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>A callee can be reached with different argument counts, and the catalogue carries ONE argument
 * index per pattern. A pattern that names the arity therefore names ONE of the shapes the callee
 * has, and silently drops the others.
 *
 * <p>Measured on the script lanes, by driving the side cars rather than by reasoning about them:
 *
 * <ul>
 *   <li>the TypeScript side car reports {@code RegExp.<init>/1} for {@code new RegExp(input)} and
 *       {@code RegExp.<init>/2} for {@code new RegExp(input, 'g')};
 *   <li>the PHP side car reports {@code \PDO.<init>/1} for {@code new PDO($dsn)} and {@code
 *       \PDO.<init>/3} for {@code new PDO($dsn, 'u', 'p')}.
 * </ul>
 *
 * <p>A pattern that said {@code RegExp.<init>/1} would match the first shape and miss the second --
 * a missed finding, with nothing to say so: the scan is green, the rule is registered, and the case
 * it was written for is not reported.
 *
 * <h2>What the three forms mean</h2>
 *
 * <p>The catalogue matches with {@code find()}, so a pattern that names no arity at all matches
 * every shape of the callee, and {@code (/\d+)?} and {@code /\d+} say the same thing. The three
 * forms are interchangeable. What the gate forbids is the fourth one: a fixed number.
 */
class SinkFamilyArityTest {

  @Test
  @DisplayName("no catalogue pattern pins the arity")
  void noCataloguePatternPinsTheArity() {
    SinkFamilyRegistry registry = ConfigLoader.loadFromClasspath().sinkFamilies();

    List<String> offenders = new ArrayList<>();
    for (SinkFamily family : registry.all()) {
      for (SinkFamily.SinkPattern pattern : family.patterns()) {
        record(offenders, family, pattern, "shared");
      }
      for (SourceLanguage language : SourceLanguage.values()) {
        for (SinkFamily.SinkPattern pattern : family.patternsFor(language)) {
          record(offenders, family, pattern, language.name());
        }
      }
    }

    assertThat(offenders)
        .as(
            "a pattern that names the arity drops the other shapes of the same callee, and the "
                + "dropped shape is a missed finding with nothing to say so; write the pattern "
                + "without the arity")
        .isEmpty();
  }

  /**
   * Record a pattern that ends in a fixed arity.
   *
   * @param offenders the list to record into
   * @param family the family the pattern belongs to
   * @param pattern the pattern
   * @param scope the language the pattern is scoped to, or "shared"
   */
  private static void record(
      List<String> offenders, SinkFamily family, SinkFamily.SinkPattern pattern, String scope) {
    String text = pattern.pattern();
    int slash = text.lastIndexOf('/');
    if (slash < 0) return;
    String tail = text.substring(slash + 1);
    if (tail.isEmpty()) return;
    for (int i = 0; i < tail.length(); i++) {
      if (!Character.isDigit(tail.charAt(i))) return;
    }
    offenders.add(
        family.name()
            + " ["
            + scope
            + "] "
            + text
            + " (arg_index "
            + pattern.argIndex()
            + ") -- the same callee is reached with other argument counts");
  }

  @Test
  @DisplayName("the catalogue has constructor patterns, and none of them pins the arity")
  void theConstructorPatternsAreArityOpen() {
    SinkFamilyRegistry registry = ConfigLoader.loadFromClasspath().sinkFamilies();

    TreeSet<String> constructors = new TreeSet<>();
    for (SinkFamily family : registry.all()) {
      for (SinkFamily.SinkPattern pattern : family.patterns()) {
        if (pattern.pattern().contains("<init>")) constructors.add(pattern.pattern());
      }
      for (SourceLanguage language : SourceLanguage.values()) {
        for (SinkFamily.SinkPattern pattern : family.patternsFor(language)) {
          if (pattern.pattern().contains("<init>")) constructors.add(pattern.pattern());
        }
      }
    }

    assertThat(constructors)
        .as("the catalogue names constructors, and a construction can take other argument counts")
        .isNotEmpty();
    assertThat(constructors)
        .as("every constructor pattern must leave the arity open")
        .allSatisfy(
            pattern -> {
              int slash = pattern.lastIndexOf('/');
              assertThat(slash < 0 || !isAllDigits(pattern.substring(slash + 1)))
                  .as("the pattern pins the arity: %s", pattern)
                  .isTrue();
            });
  }

  private static boolean isAllDigits(String text) {
    if (text.isEmpty()) return false;
    for (int i = 0; i < text.length(); i++) {
      if (!Character.isDigit(text.charAt(i))) return false;
    }
    return true;
  }
}

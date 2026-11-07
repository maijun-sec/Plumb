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
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The string builder, and the one rule that decides what a builder is.
 *
 * <p><b>Why the rule is in one place.</b> The engine asks two questions about a builder's {@code
 * append}: does it MUTATE the receiver (the accumulator's question), and does the call's RESULT
 * carry the builder's content (the passthrough's question). Both questions need the same answer to
 * "is this a builder", and the answer used to be written down twice -- once with the match on the
 * type's name and once with a slightly different spelling of it.
 *
 * <p>Two copies of one rule is a rule with two futures. The moment they disagree, one path taints
 * and the other does not, and the difference shows up as a chain that loses its taint half-way
 * rather than as an error. So the rule now lives in {@link AccumulatorCatalog}, next to the
 * accumulator's own matcher, and both callers ask it.
 *
 * <p><b>What the builder is called, in every language.</b> The owner is matched on the type's NAME
 * and case-insensitively, because the package is exactly what differs between the languages --
 * {@code java.lang.StringBuilder}, {@code kotlin.text.StringBuilder} (an alias of the Java class,
 * so its signature is the Java one), {@code System.Text.StringBuilder} -- while the name is what
 * they agree on.
 */
@DisplayName("Taint: the string builder's rule is one rule, for every language")
class StringBuilderLanguageTest {

  @TempDir Path tmp;

  private List<Finding> scan(String source, String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(base, List.of(new SqlInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().startsWith("dfa-taint-")) {
        out.add(f);
      }
    }
    return out;
  }

  @Test
  @DisplayName("the builder's chain carries the taint to the sink")
  void theBuildersChainCarriesTheTaintToTheSink() throws Exception {
    List<Finding> findings =
        scan(
            """
            class T {
              void go(String userInput) throws Exception {
                StringBuilder sb = new StringBuilder();
                String sql = sb.append("SELECT * FROM t WHERE c = '")
                    .append(userInput)
                    .append("'")
                    .toString();
                java.sql.Statement st = null;
                st.executeQuery(sql);
              }
            }
            """,
            "JavaBuilder.java");

    assertThat(findings).as("the taint reaches the sink through the builder's chain").isNotEmpty();
  }

  @Test
  @DisplayName("the rule recognises the builder in every language's spelling")
  void theRuleRecognisesTheBuilderInEveryLanguagesSpelling() {
    // The engine does not know which language a compile came from -- it sees a
    // CALL whose owner names a string builder and a method called append. So
    // the rule has to hold for every spelling of that owner.
    assertThat(AccumulatorCatalog.isStringBuilderAppend("java.lang.StringBuilder.append/1"))
        .as("the Java spelling")
        .isTrue();
    assertThat(AccumulatorCatalog.isStringBuilderAppend("kotlin.text.StringBuilder.append/1"))
        .as("the Kotlin spelling")
        .isTrue();
    assertThat(AccumulatorCatalog.isStringBuilderAppend("System.Text.StringBuilder.Append/1"))
        .as("the C# spelling")
        .isTrue();
    assertThat(AccumulatorCatalog.isStringBuilderAppend("java.lang.StringBuffer.append/1"))
        .as("the other Java builder")
        .isTrue();
  }

  @Test
  @DisplayName("an immutable string is not a builder")
  void anImmutableStringIsNotABuilder() {
    // The distinction that matters: {@code String.append} does not exist, and
    // {@code String.concat} returns a NEW string rather than mutating one. A
    // rule that matched too widely would turn every string operation into an
    // accumulator.
    assertThat(AccumulatorCatalog.isStringBuilderAppend("java.lang.String.append/1"))
        .as("an immutable string is not a builder")
        .isFalse();
    assertThat(AccumulatorCatalog.isStringBuilderAppend("java.lang.StringBuilder.toString/0"))
        .as("reading a builder is not appending to it")
        .isFalse();
    assertThat(AccumulatorCatalog.isStringBuilderAppend(null)).isFalse();
    assertThat(AccumulatorCatalog.isStringBuilderAppend("")).isFalse();
  }

  @Test
  @DisplayName("the builder's own rule and the passthrough's rule agree")
  void theBuildersOwnRuleAndThePassthroughsRuleAgree() {
    // The two questions -- does append MUTATE the builder, and does the call's
    // RESULT carry the content -- have to be answered the same way. If they
    // were not, the builder's state would be tracked while the chain's result
    // was not, and the taint would disappear exactly where the chain is.
    List<String> builders =
        List.of(
            "java.lang.StringBuilder.append/1",
            "kotlin.text.StringBuilder.append/1",
            "System.Text.StringBuilder.Append/1",
            "java.lang.StringBuffer.append/1");

    for (String sig : builders) {
      assertThat(AccumulatorCatalog.isMutator(sig))
          .as("the accumulator's own rule must recognise " + sig)
          .isTrue();
      assertThat(AccumulatorCatalog.isStringBuilderAppend(sig))
          .as("the passthrough's rule must recognise " + sig)
          .isTrue();
    }
  }
}

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
 * The switch that governs whether a string builder's {@code append} carries taint.
 *
 * <p><b>Why the switch has to be wired to be worth having.</b> The setting existed in the
 * configuration, was parsed, was carried through every copy of the config object, and had a getter
 * -- and nothing ever asked for it. A setting like that is worse than no setting: a reader of the
 * configuration file concludes the behaviour is conditional when it is unconditional, and a reader
 * of the code concludes the flag is honoured when it is not.
 *
 * <p><b>What the switch governs.</b> Two things, and they have to move together: the call's result
 * carries the appended value's taint, and the builder's own accumulated state carries it. A switch
 * that moved only one would leave the taint on the result while taking it off the receiver, which
 * is a shape nobody would expect from a switch named after the propagation.
 *
 * <p><b>What the switch does NOT govern.</b> The other accumulators -- a list, a map, a writer --
 * and the other passthroughs. Those are the shape of a defect rather than a stylistic choice, and
 * the switch is about the builder because the builder is what produces the bulk of the findings.
 */
@DisplayName("Taint: the string builder's propagation switch is wired")
class StringBuilderSwitchTest {

  @TempDir Path tmp;

  /**
   * A report built with a builder, then handed to a SQL sink.
   *
   * @param name the file's name
   * @return the file
   */
  private Path writeBuilderReport(String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(
        file,
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
        """);
    return file;
  }

  private List<Finding> scan(Path file, boolean switchOn) throws Exception {
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig config = base.withTuning(base.tuning().withTaintCallerStringConcat(switchOn));
    FindingReporter reporter =
        new DfaEngine(config, List.of(new SqlInjectionChecker()), "global", null)
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
  @DisplayName("the switch on: the builder carries the taint to the sink")
  void theSwitchOnTheBuilderCarriesTheTaintToTheSink() throws Exception {
    Path file = writeBuilderReport("SwitchOn.java");

    assertThat(scan(file, true))
        .as("with the switch on, the builder's chain carries the taint")
        .isNotEmpty();
  }

  @Test
  @DisplayName("the switch off: the builder does not carry the taint")
  void theSwitchOffTheBuilderDoesNotCarryTheTaint() throws Exception {
    Path file = writeBuilderReport("SwitchOff.java");

    assertThat(scan(file, false))
        .as(
            "with the switch off, the builder's chain is clean -- this is the whole point of the"
                + " switch, and it is what proves the setting is not decorative")
        .isEmpty();
  }

  @Test
  @DisplayName("the switch off does not touch the plain concatenation")
  void theSwitchOffDoesNotTouchThePlainConcatenation() throws Exception {
    // The switch is about the BUILDER. A plain `+` is not a builder, and
    // turning the builder's propagation off must not turn the concatenation's
    // off with it -- that would be a much bigger claim than the setting
    // makes.
    Path file = tmp.resolve("PlainConcat.java");
    Files.writeString(
        file,
        """
        class T {
          void go(String userInput) throws Exception {
            String sql = "SELECT * FROM t WHERE c = '" + userInput + "'";
            java.sql.Statement st = null;
            st.executeQuery(sql);
          }
        }
        """);

    assertThat(scan(file, false)).as("the plain concatenation still reaches the sink").isNotEmpty();
  }

  @Test
  @DisplayName("the switch off does not touch the other accumulators")
  void theSwitchOffDoesNotTouchTheOtherAccumulators() throws Exception {
    // A list is an accumulator too, and it is NOT what the switch is about.
    Path file = tmp.resolve("ListAccumulator.java");
    Files.writeString(
        file,
        """
        class T {
          void go(String userInput) throws Exception {
            java.util.List<String> parts = new java.util.ArrayList<>();
            parts.add(userInput);
            String sql = "SELECT * FROM t WHERE c = '" + parts.get(0) + "'";
            java.sql.Statement st = null;
            st.executeQuery(sql);
          }
        }
        """);

    assertThat(scan(file, false))
        .as("the list's accumulation is not the builder's, and the switch does not reach it")
        .isNotEmpty();
  }

  @Test
  @DisplayName("the default is on")
  void theDefaultIsOn() {
    assertThat(ConfigLoader.loadFromClasspath().tuning().taintCallerStringConcat())
        .as(
            "an absent configuration must not silently disable a false-negative filter -- the"
                + " same rule the path-sensitivity mode follows")
        .isTrue();
  }
}

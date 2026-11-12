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
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.summary.CalleeSummaryKeys;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.summary.FunctionSummaryCache;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The conservative caller-arg to dest rule asks the CALLEE whether its result may depend on the
 * argument. That question is not the same as "does the callee have a body".
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The engine stores one summary under several keys, and the copy it stores under the ALIAS key
 * used to set the body flag to true unconditionally. The body flag is what the conservative rule
 * asks, so a method whose body is a single {@code return "constant";} -- a method that does nothing
 * with any argument -- was announced as a method that might pass an argument through, and the rule
 * fired on it. The caller's dest was tainted by an argument the callee had thrown away.
 *
 * <p>Two methods with the same shape in the IR -- one instruction, a RETURN -- are told apart by
 * one thing: whether the returned value is the argument or a constant. The engine could not tell
 * them apart, so it treated both as "might pass the argument through".
 *
 * <h2>And a body is not the answer either</h2>
 *
 * <p>"The callee has a body" is the coarse answer to the rule's question, and it was the gate the
 * rule used. A callee with a body can still return something that cannot carry the argument:
 *
 * <pre>
 *   private String source(String x) {
 *     String y = "k";
 *     return y;
 *   }
 * </pre>
 *
 * <p>The rule's own comment says it does not over-taint "because the gate ensures only functions
 * whose result may depend on their arguments are candidates" -- so the gate has to answer THAT
 * question, and the answer is now worked out by following the returned value back through the
 * instructions that define it. Every shape that walk does not understand answers "may", so the rule
 * still fires wherever it is not sure.
 *
 * <h2>What it would catch</h2>
 *
 * <ul>
 *   <li>A copy under an alias key that claims a body the method does not have: the third test
 *       fails, and the constant-returning callee taints the caller's dest again.
 *   <li>A rule that stops at "the callee has a body": the second test fails, and the
 *       argument-passing callee stops tainting the caller's dest -- a missed detection, not a fixed
 *       false positive.
 *   <li>A gate that stops at "the callee has a body" again: the computed-constant test fails, and
 *       the caller's dest is tainted by an argument the callee threw away.
 *   <li>A lookup that stops accepting a summary that claims a body: the last test fails, and a call
 *       site falls through to the bare key, which holds another class's summary.
 * </ul>
 */
class ConservativePassthroughTest {

  @TempDir Path tmp;

  /**
   * A class whose {@code source} method returns its argument: the argument plainly reaches the
   * result, so the caller's dest has to be tainted.
   */
  private static final String PASSES_THE_ARGUMENT =
      """
      package alpha;

      public class Helper {
        void run() throws Exception {
          String input = System.getenv("PATH");
          String data = source(input);
          Runtime.getRuntime().exec(data);
        }

        private String source(String x) {
          return x;
        }
      }
      """;

  /**
   * A class whose {@code source} method returns a constant: the argument is thrown away, so the
   * caller's dest has to stay clean.
   */
  private static final String THROWS_THE_ARGUMENT_AWAY =
      """
      package beta;

      public class Helper {
        void run() throws Exception {
          String input = System.getenv("PATH");
          String data = source(input);
          Runtime.getRuntime().exec(data);
        }

        private String source(String x) {
          return "constant";
        }
      }
      """;

  /**
   * A class whose {@code source} method builds a string out of its argument: the documented
   * conservative case, where the standalone analysis cannot see the argument as tainted.
   */
  private static final String BUILDS_A_STRING =
      """
      package gamma;

      public class Helper {
        void run() throws Exception {
          String input = System.getenv("PATH");
          String data = source(input);
          Runtime.getRuntime().exec(data);
        }

        private String source(String x) {
          return "prefix" + x;
        }
      }
      """;

  /**
   * A class whose {@code source} method computes a constant and returns it: the body is not empty,
   * but the argument never reaches the result.
   */
  private static final String RETURNS_A_COMPUTED_CONSTANT =
      """
      package delta;

      public class Helper {
        void run() throws Exception {
          String input = System.getenv("PATH");
          String data = source(input);
          Runtime.getRuntime().exec(data);
        }

        private String source(String x) {
          String y = "k";
          return y;
        }
      }
      """;

  /**
   * A class whose {@code source} method hands its argument to a call: the result is whatever that
   * call makes of the argument, so the argument reaches the result.
   */
  private static final String PASSES_THE_ARGUMENT_TO_A_CALL =
      """
      package epsilon;

      public class Helper {
        void run() throws Exception {
          String input = System.getenv("PATH");
          String data = source(input);
          Runtime.getRuntime().exec(data);
        }

        private String source(String x) {
          return x.trim();
        }
      }
      """;

  /**
   * A class whose {@code source} method builds a string out of its argument and a local: the local
   * is a constant, and the argument is still one side of the string.
   */
  private static final String BUILDS_A_STRING_FROM_A_LOCAL =
      """
      package theta;

      public class Helper {
        void run() throws Exception {
          String input = System.getenv("PATH");
          String data = source(input);
          Runtime.getRuntime().exec(data);
        }

        private String source(String x) {
          String y = "k";
          return y + x;
        }
      }
      """;

  @Test
  @DisplayName("a callee that returns its argument taints the caller's dest")
  void aCalleeThatReturnsItsArgumentTaintsTheCallersDest() throws Exception {
    List<String> findings = run(PASSES_THE_ARGUMENT, "alpha");

    assertThat(findings)
        .as("the argument reaches the callee's result, so the caller's dest is tainted")
        .hasSize(1);
    assertThat(findings.get(0)).contains("command-injection");
  }

  @Test
  @DisplayName("a callee that builds a string out of its argument taints the caller's dest")
  void aCalleeThatBuildsAStringTaintsTheCallersDest() throws Exception {
    List<String> findings = run(BUILDS_A_STRING, "gamma");

    assertThat(findings)
        .as("the string is built from the argument, so the caller's dest is tainted")
        .hasSize(1);
    assertThat(findings.get(0)).contains("command-injection");
  }

  @Test
  @DisplayName("a callee that returns a constant does not taint the caller's dest")
  void aCalleeThatReturnsAConstantDoesNotTaintTheCallersDest() throws Exception {
    List<String> findings = run(THROWS_THE_ARGUMENT_AWAY, "beta");

    assertThat(findings)
        .as(
            "the callee returns a constant, so the argument never reaches the result and the "
                + "caller's dest has to stay clean")
        .isEmpty();
  }

  @Test
  @DisplayName("a callee that computes a constant does not taint the caller's dest")
  void aCalleeThatComputesAConstantDoesNotTaintTheCallersDest() throws Exception {
    List<String> findings = run(RETURNS_A_COMPUTED_CONSTANT, "delta");

    assertThat(findings)
        .as(
            "the callee has a body, but the value it returns is a constant it computed, so the "
                + "argument never reaches the result and the caller's dest has to stay clean")
        .isEmpty();
  }

  @Test
  @DisplayName("a callee that hands its argument to a call taints the caller's dest")
  void aCalleeThatHandsItsArgumentToACallTaintsTheCallersDest() throws Exception {
    List<String> findings = run(PASSES_THE_ARGUMENT_TO_A_CALL, "epsilon");

    assertThat(findings)
        .as("the result is whatever the call makes of the argument, so the argument reaches it")
        .hasSize(1);
    assertThat(findings.get(0)).contains("command-injection");
  }

  @Test
  @DisplayName("a callee that builds a string from a local and its argument taints the dest")
  void aCalleeThatBuildsAStringFromALocalAndItsArgumentTaintsTheDest() throws Exception {
    List<String> findings = run(BUILDS_A_STRING_FROM_A_LOCAL, "theta");

    assertThat(findings)
        .as("the argument is one side of the string, so it reaches the result")
        .hasSize(1);
    assertThat(findings.get(0)).contains("command-injection");
  }

  @Test
  @DisplayName("the copy under an alias key keeps the method's own answer")
  void theCopyUnderAnAliasKeyKeepsTheMethodsOwnAnswer() {
    FunctionSummary passesItOn =
        new FunctionSummary.Builder("alpha.Helper :: source/1").markMayReturnArgument().build();
    FunctionSummary throwsItAway = new FunctionSummary.Builder("beta.Helper :: source/1").build();

    FunctionSummary copyOfPassesItOn = passesItOn.aliasedAs("alpha.Helper :: source/1");
    FunctionSummary copyOfThrowsItAway = throwsItAway.aliasedAs("beta.Helper :: source/1");

    assertThat(copyOfPassesItOn.claimsBody()).as("the alias key says the method IS HERE").isTrue();
    assertThat(copyOfThrowsItAway.claimsBody())
        .as("the alias key says the method IS HERE")
        .isTrue();
    assertThat(copyOfPassesItOn.mayReturnArgument())
        .as("the method passes the argument on, and the copy says so")
        .isTrue();
    assertThat(copyOfThrowsItAway.mayReturnArgument())
        .as("the method throws the argument away, and the copy must not claim otherwise")
        .isFalse();
    assertThat(copyOfThrowsItAway.hasBody())
        .as("the copy must not claim a body the method does not have")
        .isFalse();
  }

  @Test
  @DisplayName("a call site finds its own class's copy instead of another class's summary")
  void aCallSiteFindsItsOwnClasssCopy() {
    FunctionSummaryCache cache =
        new FunctionSummaryCache(ConfigLoader.loadFromClasspath().tuning());
    FunctionSummary alphaCopy =
        new FunctionSummary.Builder("alpha")
            .markMayReturnArgument()
            .build()
            .aliasedAs(CalleeSummaryKeys.aliasKey("alpha.Helper", "source/1"));
    FunctionSummary betaCopy =
        new FunctionSummary.Builder("beta")
            .build()
            .aliasedAs(CalleeSummaryKeys.aliasKey("beta.Helper", "source/1"));
    cache.put(CalleeSummaryKeys.aliasKey("alpha.Helper", "source/1"), alphaCopy);
    cache.put(CalleeSummaryKeys.aliasKey("beta.Helper", "source/1"), betaCopy);
    // The bare key is written too, and the first class to be written owns it.
    cache.put("source/1", alphaCopy);

    CalleeSummaryKeys.Resolution beta = CalleeSummaryKeys.lookup(cache, "source/1", "beta.Helper");

    assertThat(beta.isEmpty())
        .as("the copy carries no facts, but the method is here and that is the answer")
        .isFalse();
    assertThat(beta.key())
        .as("the second class's call site must not read the first class's summary")
        .isEqualTo(CalleeSummaryKeys.aliasKey("beta.Helper", "source/1"));
    assertThat(beta.summary().mayReturnArgument())
        .as("the second class's method throws its argument away")
        .isFalse();
  }

  /**
   * Run the engine over one fixture and report the findings, one line each.
   *
   * @param source the class to analyse
   * @param pkg the package it lives in
   * @return the findings, sorted
   */
  private List<String> run(String source, String pkg) throws Exception {
    Path dir = tmp.resolve(pkg);
    Files.createDirectories(dir);
    Path file = dir.resolve("Helper.java");
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    java.util.function.Supplier<List<DfaChecker>> factory =
        () -> List.of(new CommandInjectionChecker());
    FindingReporter reporter =
        new DfaEngine(
                ConfigLoader.loadFromClasspath(),
                List.of(new CommandInjectionChecker()),
                "global",
                null,
                0,
                factory)
            .runWithGlobalStore(List.of(program), 0);
    List<String> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      out.add(f.ruleId() + "@" + f.file() + ":" + f.line());
    }
    out.sort(String::compareTo);
    return out;
  }
}

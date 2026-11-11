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
package org.plumb.dfa.summary;

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
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * Two classes that declare a method of the same name are two different methods.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>A summary describes one method, so the key it is stored under has to name the class that
 * DECLARES it. The engine writes the summary under the declaring class, and the call site has to
 * ask under the same class.
 *
 * <p>A call that names no class at all -- an unqualified call, which is the most common call there
 * is -- names the CALLER's own class, and the caller knows that class. So the lookup asks under it
 * first.
 *
 * <p>It used to ask under the bare key first: the key that holds the method name and the arity and
 * nothing else. Every class that declares a method of that name writes under that key, so it holds
 * ONE class's summary -- whichever was written first -- and every other class's call site read it.
 * The taint that reached the sink was then another method's taint, and the finding was attributed
 * to a file that never contained it.
 *
 * <h2>What it would catch</h2>
 *
 * <ul>
 *   <li>A lookup that asks under the bare key before the class-qualified one: the two classes read
 *       each other's summary, and the second half of this test fails.
 *   <li>A key builder that drops the package: the two classes' keys collide, and the same test
 *       fails.
 *   <li>An engine that writes the summary under one class and the lookup asks under another: the
 *       lookup finds nothing, and the finding count drops to zero.
 * </ul>
 *
 * <p>The fixture is built so that the two classes are genuinely different: one of them propagates
 * its argument's taint to its return value, the other returns a constant. So the two methods have
 * different facts, and a scan that mixes them up produces a finding in the wrong file -- or in both
 * files, or in neither.
 */
class CalleeSummaryKeysTest {

  @TempDir Path tmp;

  /**
   * The class in the first package: its {@code source} method returns a value the program did not
   * choose, so the command it builds is tainted THROUGH the method's return value.
   */
  private static final String ALPHA =
      """
      package alpha;

      public class Helper {
        void run() throws Exception {
          String data = source();
          Runtime.getRuntime().exec(data);
        }

        private String source() {
          return System.getenv("PATH");
        }
      }
      """;

  /**
   * The class in the second package: the SAME class name and the SAME method name, but its {@code
   * source} method returns a constant, so the command it builds is clean.
   */
  private static final String BETA =
      """
      package beta;

      public class Helper {
        void run() throws Exception {
          String data = source();
          Runtime.getRuntime().exec(data);
        }

        private String source() {
          return "constant";
        }
      }
      """;

  private FunctionSummaryCache newCache() {
    return new FunctionSummaryCache(ConfigLoader.loadFromClasspath().tuning());
  }

  private static FunctionSummary summaryWithTaintCause() {
    return new FunctionSummary.Builder("tainted").markHasBody().recordReturnTaintCause(0).build();
  }

  private static FunctionSummary summaryWithoutTaintCause() {
    return new FunctionSummary.Builder("clean").markHasBody().build();
  }

  @Test
  @DisplayName("a call that names no class asks under the caller's own class first")
  void anUnqualifiedCallAsksUnderTheCallersClassFirst() {
    List<String> keys = CalleeSummaryKeys.keysFor("source/0", "alpha.Helper");

    assertThat(keys)
        .as("the caller's class-qualified key has to come first, or the bare key answers first")
        .startsWith("alpha.Helper :: source/0", "alpha.Helper.source/0");
    assertThat(keys)
        .as("the bare key addresses every class of that method name, so it is the last resort")
        .endsWith("source/0");
  }

  @Test
  @DisplayName("a call that names its receiver's class asks under that class first")
  void aQualifiedCallAsksUnderTheNamedClassFirst() {
    List<String> keys = CalleeSummaryKeys.keysFor("alpha.Helper.source/0", null);

    assertThat(keys.indexOf("alpha.Helper.source/0"))
        .as("the fully qualified class is the specific one")
        .isLessThan(keys.indexOf("Helper.source/0"));
    assertThat(keys.indexOf("Helper.source/0"))
        .as("the simple class name addresses every package's class of that name")
        .isLessThan(keys.indexOf("source/0"));
  }

  @Test
  @DisplayName("the key the engine writes is one of the keys a call site asks under")
  void theWriteSideAndTheReadSideAgree() {
    String alias = CalleeSummaryKeys.aliasKey("alpha.Helper", "source/0");
    String qualified = CalleeSummaryKeys.qualifiedKey("alpha.Helper", "source/0");

    assertThat(CalleeSummaryKeys.keysFor("source/0", "alpha.Helper"))
        .as("the alias key the engine writes has to be one a call site asks under")
        .contains(alias, qualified);
  }

  @Test
  @DisplayName("two classes of the same simple name do not read each other's summary")
  void twoClassesOfTheSameNameDoNotReadEachOthersSummary() {
    FunctionSummaryCache cache = newCache();
    // What the engine writes for the two classes: the alias key, the qualified key, and the bare
    // key the two of them share.
    cache.put(CalleeSummaryKeys.aliasKey("alpha.Helper", "source/0"), summaryWithTaintCause());
    cache.put(CalleeSummaryKeys.qualifiedKey("alpha.Helper", "source/0"), summaryWithTaintCause());
    cache.put(CalleeSummaryKeys.aliasKey("beta.Helper", "source/0"), summaryWithoutTaintCause());
    cache.put(
        CalleeSummaryKeys.qualifiedKey("beta.Helper", "source/0"), summaryWithoutTaintCause());
    // The bare key is written too, and the first class to be written owns it.
    cache.put("source/0", summaryWithTaintCause());

    CalleeSummaryKeys.Resolution alpha =
        CalleeSummaryKeys.lookup(cache, "source/0", "alpha.Helper");
    CalleeSummaryKeys.Resolution beta = CalleeSummaryKeys.lookup(cache, "source/0", "beta.Helper");

    assertThat(alpha.summary().returnTaintCauses())
        .as("the first class's call site must find the first class's summary")
        .containsExactly(0);
    assertThat(beta.summary().returnTaintCauses())
        .as("the second class's call site must NOT find the first class's summary")
        .isEmpty();
    assertThat(alpha.key())
        .as("the two call sites must not share one key")
        .isNotEqualTo(beta.key());
  }

  @Test
  @DisplayName("a lookup that finds nothing says so instead of inventing a summary")
  void aLookupThatFindsNothingIsEmpty() {
    FunctionSummaryCache cache = newCache();

    CalleeSummaryKeys.Resolution resolution =
        CalleeSummaryKeys.lookup(cache, "nothing/0", "alpha.Helper");

    assertThat(resolution.isEmpty()).isTrue();
    assertThat(resolution.summary()).isNotNull();
  }

  /**
   * Run the engine over the two classes and report the findings, one line each.
   *
   * @return the findings, sorted
   */
  private List<String> runTwoClasses() throws Exception {
    List<TacProgram> programs = new ArrayList<>();
    for (String[] fixture : new String[][] {{"alpha", ALPHA}, {"beta", BETA}}) {
      Path dir = tmp.resolve(fixture[0]);
      Files.createDirectories(dir);
      Path file = dir.resolve("Helper.java");
      Files.writeString(file, fixture[1]);
      ParsedSource ps = new JavaSourceParser().parse(file);
      programs.add(new TacTranslator(ps).translate());
    }
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
            .runWithGlobalStore(programs, 0);
    List<String> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      out.add(f.ruleId() + "@" + f.file() + ":" + f.line());
    }
    out.sort(String::compareTo);
    return out;
  }

  @Test
  @DisplayName("two classes of the same name are two different methods, end to end")
  void twoClassesOfTheSameNameAreTwoDifferentMethods() throws Exception {
    List<String> findings = runTwoClasses();

    assertThat(findings)
        .as(
            "the fixture must produce a finding in the class whose source method returns a tainted"
                + " value, and none in the class whose method returns a constant")
        .hasSize(1);
    assertThat(findings.get(0))
        .as("the finding must be the tainted class's, not the clean class's")
        .contains("alpha")
        .doesNotContain("beta");
  }

  @Test
  @DisplayName("the engine writes one summary per class for a same-named method")
  void theEngineWritesOneSummaryPerClass() throws Exception {
    FunctionSummaryCache cache = runTwoClassesAndKeepTheCache();

    assertThat(cache.contains(CalleeSummaryKeys.aliasKey("alpha.Helper", "source/0")))
        .as("the first class's summary must be stored under the first class")
        .isTrue();
    assertThat(cache.contains(CalleeSummaryKeys.aliasKey("beta.Helper", "source/0")))
        .as("the second class's summary must be stored under the second class")
        .isTrue();
    CalleeSummaryKeys.Resolution alpha =
        CalleeSummaryKeys.lookup(cache, "source/0", "alpha.Helper");
    CalleeSummaryKeys.Resolution beta = CalleeSummaryKeys.lookup(cache, "source/0", "beta.Helper");
    assertThat(alpha.summary().returnOps())
        .as("the first class's method returns a value the program did not choose")
        .anyMatch(op -> op instanceof Operation.TaintPropagate);
    assertThat(beta.summary().returnOps())
        .as("the second class's method returns a constant")
        .noneMatch(op -> op instanceof Operation.TaintPropagate);
  }

  /** Run the engine over the two classes and hand back the summary cache it filled. */
  private FunctionSummaryCache runTwoClassesAndKeepTheCache() throws Exception {
    List<TacProgram> programs = new ArrayList<>();
    for (String[] fixture : new String[][] {{"alpha", ALPHA}, {"beta", BETA}}) {
      Path dir = tmp.resolve(fixture[0]);
      Files.createDirectories(dir);
      Path file = dir.resolve("Helper.java");
      Files.writeString(file, fixture[1]);
      ParsedSource ps = new JavaSourceParser().parse(file);
      programs.add(new TacTranslator(ps).translate());
    }
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
            .runWithGlobalStore(programs, 0);
    AnalysisContext ctx = reporter.context();
    assertThat(ctx)
        .as("the reporter has to hold the context for the cache to be reachable")
        .isNotNull();
    return ctx.summaryCache();
  }
}

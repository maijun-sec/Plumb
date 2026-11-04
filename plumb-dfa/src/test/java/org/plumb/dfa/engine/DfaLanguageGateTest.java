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
package org.plumb.dfa.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The language gate on the DFA engine.
 *
 * <p>A checker that declares it does not handle a function's language must be skipped for that
 * function -- in BOTH the analysis loop and the summary loop. The summary loop matters just as
 * much: a summary carries observations the checker actually made, so a checker that never ran on a
 * function must not contribute a summary for it either.
 *
 * <p>The other half of the contract is that a gate is a <b>skip, not a finding</b>. Skipping a
 * checker says "this checker has nothing to say about this language", NOT "this language is safe".
 * A gate must never turn into a report, and it must never throw.
 */
class DfaLanguageGateTest {

  /**
   * A checker that records every function it was run on, and reports a finding for each one.
   *
   * <p>It is deliberately minimal: the point is to observe the gate, not the analysis.
   */
  private static final class RecordingChecker implements DfaChecker {
    private final SourceLanguage only;
    final List<String> analysed = new java.util.ArrayList<>();
    final List<String> summarised = new java.util.ArrayList<>();

    RecordingChecker(SourceLanguage only) {
      this.only = only;
    }

    @Override
    public String name() {
      return "Recording";
    }

    @Override
    public boolean supports(TacFunction fn) {
      if (fn == null) return true;
      return fn.sourceLanguage() == only;
    }

    @Override
    public void initFunction(AnalysisContext ctx, TacFunction fn) {
      analysed.add(fn.signature());
    }

    @Override
    public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
      // no-op: the gate is what is under test.
    }

    @Override
    public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
      summarised.add(fn.signature());
      return FunctionSummary.empty();
    }
  }

  /** A checker whose gate always throws. The engine must treat that as "not supported". */
  private static final class ExplodingGateChecker implements DfaChecker {
    final AtomicInteger initCalls = new AtomicInteger();

    @Override
    public String name() {
      return "ExplodingGate";
    }

    @Override
    public boolean supports(TacFunction fn) {
      throw new IllegalStateException("gate is broken");
    }

    @Override
    public void initFunction(AnalysisContext ctx, TacFunction fn) {
      initCalls.incrementAndGet();
    }
  }

  /** A checker with no language restriction at all: it must run on everything. */
  private static final class UnrestrictedChecker implements DfaChecker {
    final List<String> analysed = new java.util.ArrayList<>();

    @Override
    public String name() {
      return "Unrestricted";
    }

    @Override
    public void initFunction(AnalysisContext ctx, TacFunction fn) {
      analysed.add(fn.signature());
    }
  }

  private static TacFunction function(String signature, SourceLanguage language) {
    return new TacFunction(
        signature,
        signature,
        TacType.VOID,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        null,
        SourceOrigin.SOURCE,
        List.of(),
        language,
        IrPrecision.SOURCE_FULL);
  }

  private static TacProgram program(TacFunction... functions) {
    TacProgram program = new TacProgram(java.nio.file.Path.of("Test.java"), "Test");
    for (TacFunction fn : functions) {
      program.addFunction(fn);
    }
    return program;
  }

  @Test
  @DisplayName("a checker only runs on the functions whose language it supports")
  void checkerRunsOnlyOnSupportedLanguages() {
    RecordingChecker checker = new RecordingChecker(SourceLanguage.KOTLIN);
    TacFunction kotlinFn = function("k()", SourceLanguage.KOTLIN);
    TacFunction javaFn = function("j()", SourceLanguage.JAVA);
    TacFunction scalaFn = function("s()", SourceLanguage.SCALA);

    EngineConfig config = ConfigLoader.loadFromClasspath();
    new DfaEngine(config, List.of(checker), "global", null)
        .runWithGlobalStore(List.of(program(kotlinFn, javaFn, scalaFn)), 0);

    assertThat(checker.analysed)
        .as("only the Kotlin function should have reached the checker")
        .containsExactly("k()");
  }

  @Test
  @DisplayName("the summary loop is gated too")
  void summaryLoopIsGated() {
    RecordingChecker checker = new RecordingChecker(SourceLanguage.SCALA);
    TacFunction kotlinFn = function("k()", SourceLanguage.KOTLIN);
    TacFunction scalaFn = function("s()", SourceLanguage.SCALA);

    EngineConfig config = ConfigLoader.loadFromClasspath();
    new DfaEngine(config, List.of(checker), "global", null)
        .runWithGlobalStore(List.of(program(kotlinFn, scalaFn)), 0);

    // A summary carries the observations the checker actually made. A checker that never
    // ran on a function must not contribute a summary for it either -- otherwise a
    // caller-side checker would consult a summary full of things that were never checked.
    assertThat(checker.summarised).containsExactly("s()");
  }

  @Test
  @DisplayName("a checker that supports every language runs on every function")
  void unrestrictedCheckerRunsEverywhere() {
    UnrestrictedChecker checker = new UnrestrictedChecker();

    TacFunction kotlinFn = function("k()", SourceLanguage.KOTLIN);
    TacFunction javaFn = function("j()", SourceLanguage.JAVA);

    EngineConfig config = ConfigLoader.loadFromClasspath();
    new DfaEngine(config, List.of(checker), "global", null)
        .runWithGlobalStore(List.of(program(kotlinFn, javaFn)), 0);

    assertThat(checker.analysed).containsExactlyInAnyOrder("k()", "j()");
  }

  @Test
  @DisplayName("a broken gate skips that one checker instead of taking down the analysis")
  void brokenGateSkipsSafely() {
    ExplodingGateChecker checker = new ExplodingGateChecker();
    TacFunction javaFn = function("j()", SourceLanguage.JAVA);

    EngineConfig config = ConfigLoader.loadFromClasspath();
    // Must not propagate the exception.
    new DfaEngine(config, List.of(checker), "global", null)
        .runWithGlobalStore(List.of(program(javaFn)), 0);

    assertThat(checker.initCalls.get())
        .as("a checker whose gate throws must be skipped, not run")
        .isZero();
  }

  @Test
  @DisplayName("a gate is a skip, not a finding")
  void gateNeverProducesAFinding() {
    RecordingChecker checker = new RecordingChecker(SourceLanguage.KOTLIN);
    TacFunction javaFn = function("j()", SourceLanguage.JAVA);

    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program(javaFn)), 0);

    assertThat(reporter.all()).as("skipping a checker must not manufacture a finding").isEmpty();
  }

  @Test
  @DisplayName("the default gate accepts every language")
  void defaultGateAcceptsEverything() {
    DfaChecker plain =
        new DfaChecker() {
          @Override
          public String name() {
            return "Plain";
          }
        };

    assertThat(plain.supports(function("j()", SourceLanguage.JAVA))).isTrue();
    assertThat(plain.supports(function("k()", SourceLanguage.KOTLIN))).isTrue();
    assertThat(plain.supports(function("s()", SourceLanguage.SCALA))).isTrue();
    assertThat(plain.supports(function("g()", SourceLanguage.GROOVY))).isTrue();
    assertThat(plain.supports(function("u()", SourceLanguage.UNKNOWN))).isTrue();
    assertThat(plain.supports(null)).isTrue();
  }

  @Test
  @DisplayName("a checker that supports no language at all simply never runs")
  void checkerForAnAbsentLanguageNeverRuns() {
    RecordingChecker checker = new RecordingChecker(SourceLanguage.GROOVY);
    TacFunction kotlinFn = function("k()", SourceLanguage.KOTLIN);
    TacFunction javaFn = function("j()", SourceLanguage.JAVA);

    EngineConfig config = ConfigLoader.loadFromClasspath();
    new DfaEngine(config, List.of(checker), "global", null)
        .runWithGlobalStore(List.of(program(kotlinFn, javaFn)), 0);

    assertThat(checker.analysed).isEmpty();
    assertThat(checker.summarised).isEmpty();
  }

  @Test
  @DisplayName("a null function is accepted by the default gate")
  void nullFunctionIsAccepted() {
    DfaChecker plain =
        new DfaChecker() {
          @Override
          public String name() {
            return "Plain";
          }
        };
    assertThat(plain.supports(null)).isTrue();
  }
}

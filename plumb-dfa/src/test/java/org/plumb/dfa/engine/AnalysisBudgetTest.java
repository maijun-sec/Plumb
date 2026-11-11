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

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The analysis budget must be consumed, not merely declared.
 *
 * <p><b>What went wrong.</b> {@code TuningConfig} carried {@code contextDepth}, {@code
 * worklistMaxSize}, {@code z3TimeoutMs}, {@code perFunctionTimeoutMs}, {@code reverseWalkMaxDepth}
 * and {@code summaryCacheSize} since it was written. The engine read <em>none</em> of them. A user
 * who lowered {@code per_function_timeout_ms} saw no change in behaviour and no warning: the knob
 * was decoration.
 *
 * <p>A budget that is not enforced is worse than no budget at all, because it teaches the user that
 * the knob works. They then trust an analysis that is not actually bounded.
 *
 * <p><b>What these tests pin.</b> Three things, and the third is the one that matters most:
 *
 * <ol>
 *   <li>The budget object reports exhaustion when a cap is passed.
 *   <li>The engine reads the configured limits into the budget.
 *   <li>The exhaustion is <b>visible</b> -- it reaches a report the user can read. A budget that
 *       caps the work but records nothing is a silent truncation, which is the failure mode this
 *       whole mechanism exists to prevent.
 * </ol>
 */
class AnalysisBudgetTest {

  /**
   * A budget with no limits must never report exhaustion.
   *
   * <p>This is the control. Without it, a test that asserts "exhaustion was reported" could pass
   * because the budget always reports exhaustion.
   */
  @Test
  void anUnlimitedBudgetNeverReportsExhaustion() {
    AnalysisBudget budget = AnalysisBudget.unlimited();

    for (int i = 0; i < 10_000; i++) {
      assertThat(budget.accountFact("f")).isTrue();
      assertThat(budget.checkPerFunctionTime(System.nanoTime(), "f")).isTrue();
      assertThat(budget.checkSolverTime(System.nanoTime(), "f")).isTrue();
    }

    assertThat(budget.report().complete()).isTrue();
    assertThat(budget.report().exhaustions()).isEmpty();
  }

  /** The worklist cap must stop fact tracking once it is passed. */
  @Test
  void theWorklistCapStopsFactTracking() {
    AnalysisBudget budget = new AnalysisBudget(0L, 0L, 10, 0, 0, 0);

    for (int i = 0; i < 10; i++) {
      assertThat(budget.accountFact("f")).as("fact %d is within the cap", i).isTrue();
    }

    assertThat(budget.accountFact("f")).as("the fact past the cap must be refused").isFalse();
    assertThat(budget.report().complete()).isFalse();
    assertThat(budget.report().exhaustions())
        .anySatisfy(
            e -> {
              assertThat(e.dimension()).isEqualTo(AnalysisBudget.Dimension.WORKLIST_SIZE);
              assertThat(e.limit()).isEqualTo(10L);
              assertThat(e.subject()).isEqualTo("f");
            });
  }

  /** The per-function time cap must fire once the function has taken too long. */
  @Test
  void thePerFunctionTimeCapFires() {
    AnalysisBudget budget = new AnalysisBudget(1L, 0L, 0, 0, 0, 0);

    // A start time far enough in the past that the 1ms budget is definitely spent.
    long longAgo = System.nanoTime() - 50_000_000L;

    assertThat(budget.checkPerFunctionTime(longAgo, "com.example.Thing.handle")).isFalse();
    assertThat(budget.report().exhaustions())
        .anySatisfy(
            e -> {
              assertThat(e.dimension()).isEqualTo(AnalysisBudget.Dimension.PER_FUNCTION_TIME);
              assertThat(e.subject())
                  .as("the record must name what was being analysed")
                  .isEqualTo("com.example.Thing.handle");
            });
  }

  /** The solver time cap must fire independently of the per-function cap. */
  @Test
  void theSolverTimeCapFires() {
    AnalysisBudget budget = new AnalysisBudget(0L, 1L, 0, 0, 0, 0);

    long longAgo = System.nanoTime() - 50_000_000L;

    assertThat(budget.checkSolverTime(longAgo, "solver")).isFalse();
    assertThat(budget.report().exhaustions())
        .anySatisfy(e -> assertThat(e.dimension()).isEqualTo(AnalysisBudget.Dimension.SOLVER_TIME));
  }

  /** The budget must read its limits from the tuning configuration, not from hard-coded values. */
  @Test
  void theBudgetReadsTheConfiguredLimits() {
    org.plumb.dfa.config.TuningConfig tuning =
        new org.plumb.dfa.config.TuningConfig(
            3, // contextDepth
            42, // worklistMaxSize
            7, // z3TimeoutMs
            11, // summaryCacheSize
            5, // reverseWalkMaxDepth
            true, // preScanEnabled
            false, // libraryCallClassification
            true, // taintCallerStringConcat
            "none", // pathSensitivity
            "rule", // reportingGroupBy
            "json", // outputFormat
            false, // parallel
            99 // perFunctionTimeoutMs
            );

    AnalysisBudget budget = new AnalysisBudget(tuning);

    assertThat(budget.contextDepth()).isEqualTo(3);
    assertThat(budget.worklistMaxSize()).isEqualTo(42);
    assertThat(budget.solverTimeoutMs()).isEqualTo(7L);
    assertThat(budget.summaryCacheSize()).isEqualTo(11);
    assertThat(budget.reverseWalkMaxDepth()).isEqualTo(5);
    assertThat(budget.perFunctionTimeoutMs()).isEqualTo(99L);

    Map<AnalysisBudget.Dimension, Long> limits = budget.report().limits();
    assertThat(limits.get(AnalysisBudget.Dimension.WORKLIST_SIZE)).isEqualTo(42L);
    assertThat(limits.get(AnalysisBudget.Dimension.PER_FUNCTION_TIME)).isEqualTo(99L);
  }

  /**
   * The exhaustion record must say what ran out and on what -- a bare "budget exceeded" is not
   * actionable.
   */
  @Test
  void theExhaustionRecordIsActionable() {
    AnalysisBudget budget = new AnalysisBudget(0L, 0L, 1, 0, 0, 0);

    budget.accountFact("first");
    budget.accountFact("com.example.Thing.handle");

    AnalysisBudget.BudgetReport report = budget.report();
    assertThat(report.complete()).isFalse();
    assertThat(report.summary())
        .contains("analysis incomplete")
        .contains("WORKLIST_SIZE")
        .contains("com.example.Thing.handle");
  }

  /** A complete run's summary must not read like a failure. */
  @Test
  void aCompleteRunSaysSo() {
    AnalysisBudget budget = AnalysisBudget.unlimited();
    assertThat(budget.report().summary()).isEqualTo("analysis complete within budget");
  }

  /**
   * A limit that fires a million times is ONE record with a count, not a million records.
   *
   * <p><b>What went wrong.</b> Every fact past the worklist cap recorded its own exhaustion, and
   * every record was a separate object in an unbounded list. A scan of one jar produced 557039
   * records and a summary string built by concatenating all of them; the summary was written to the
   * log, into the SARIF document and into the scan's completeness note, so the report of the budget
   * grew larger than the analysis it was reporting on. The scan died of a heap exhaustion while
   * writing it, and produced no report at all.
   *
   * <p>The number of records is the number of LIMITS that were hit. The number of firings is a
   * count on one of them.
   */
  @Test
  void aLimitThatFiresRepeatedlyIsOneRecordWithACount() {
    AnalysisBudget budget = new AnalysisBudget(0L, 0L, 10, 0, 0, 0);

    for (int i = 0; i < 10; i++) {
      assertThat(budget.accountFact("f")).isTrue();
    }
    for (int i = 0; i < 9_990; i++) {
      assertThat(budget.accountFact("com.example.Thing.handle")).isFalse();
    }

    AnalysisBudget.BudgetReport report = budget.report();
    assertThat(report.exhaustions())
        .as("one limit was hit, so there is one record -- not one per firing")
        .hasSize(1);
    assertThat(report.exhaustions().get(0).occurrences()).isEqualTo(9_990L);
    assertThat(report.exhaustions().get(0).subject()).isEqualTo("com.example.Thing.handle");
  }

  /**
   * The summary must stay short, because it is written into the log and into the report.
   *
   * <p>The summary is the only place the user is told that the analysis stopped early, so it cannot
   * be the thing that exhausts the heap. Its length is bounded by the number of limits and by the
   * number of sampled subjects per limit -- not by the number of events.
   */
  @Test
  void theSummaryStaysShort() {
    AnalysisBudget budget = new AnalysisBudget(0L, 0L, 1, 0, 0, 0);

    for (int i = 0; i < 100_000; i++) {
      budget.accountFact("com.example.Thing.handle");
    }

    String summary = budget.report().summary();
    assertThat(summary.length())
        .as("the summary must not grow with the number of events")
        .isLessThan(1_000);
    // The cap of one lets the first fact through, so 99999 of the 100000 were refused.
    assertThat(summary).contains("WORKLIST_SIZE").contains("99999 time(s)");
  }

  /**
   * The subjects a limit was hit on are sampled, so a long list of them cannot grow the report.
   *
   * <p>Which functions ran out of memory is worth saying, but a scan where half a million functions
   * did is not a list anyone reads. The record keeps the first few and the count of the rest.
   */
  @Test
  void theSubjectsAreSampled() {
    AnalysisBudget budget = AnalysisBudget.unlimited();

    for (int i = 0; i < 500; i++) {
      budget.record(
          AnalysisBudget.Dimension.WORKLIST_SIZE,
          Integer.MAX_VALUE,
          "com.example.Thing.handle" + i + " (out of memory)");
    }

    AnalysisBudget.BudgetReport report = budget.report();
    assertThat(report.exhaustions()).hasSize(1);
    assertThat(report.exhaustions().get(0).occurrences()).isEqualTo(500L);
    assertThat(report.exhaustions().get(0).samples())
        .as("the sample is bounded, so the record cannot grow with the events")
        .hasSizeLessThanOrEqualTo(8);
    assertThat(report.summary())
        .contains("com.example.Thing.handle0")
        .contains("500 time(s)")
        .hasSizeLessThan(1_000);
  }
}

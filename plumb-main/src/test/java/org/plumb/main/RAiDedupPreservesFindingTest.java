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
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.Finding;
import org.plumb.reporter.FlowStep;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;

/**
 * The AI dedup pass must not change a finding apart from its verdict.
 *
 * <p>THE DEFECT THIS PINS. {@code ScanRunner.withAiVerdict} rebuilt the immutable {@link Finding}
 * through the TEN-argument constructor, which hardcodes {@code sinkFunction} to {@code null} (see
 * {@code Finding}'s 10-arg constructor, the {@code null} in the {@code sinkFunction} slot).
 *
 * <p>So every finding that went through the AI pass came out with its {@code sink_function} GONE,
 * while the same finding scanned WITHOUT the AI pass kept it. The NDJSON emitter only writes {@code
 * sink_function} when it is non-null, so the field simply vanished from the report.
 *
 * <p>WHY THAT MATTERS BEYOND COSMETICS. The Juliet harness pins a case to the sink callee via
 * {@code sink_function}, so a case that matched before the AI pass stopped matching after it. A
 * measurement that scores the AI pass on a field the AI pass itself destroys measures the
 * destruction, not the judgement.
 */
class RAiDedupPreservesFindingTest {

  private static final String SINK = "java.sql.Statement.executeQuery/1";

  @Test
  void attachingAVerdictKeepsTheSinkFunction() {
    Finding original = findingWithSinkFunction(SINK);

    Finding judged = ScanRunner.withAiVerdictForTest(original, "TP", "the value reaches the sink");

    assertThat(judged.sinkFunction())
        .as("the AI verdict must not erase the sink the finding names")
        .isEqualTo(SINK);
  }

  @Test
  void attachingAVerdictKeepsEveryOtherField() {
    Finding original = findingWithSinkFunction(SINK);

    Finding judged = ScanRunner.withAiVerdictForTest(original, "FP", "sanitised upstream");

    assertThat(judged.file()).isEqualTo(original.file());
    assertThat(judged.line()).isEqualTo(original.line());
    assertThat(judged.column()).isEqualTo(original.column());
    assertThat(judged.message()).isEqualTo(original.message());
    assertThat(judged.severity()).isEqualTo(original.severity());
    assertThat(judged.dataflowHead()).isEqualTo(original.dataflowHead());
    assertThat(judged.dataflowSteps()).isEqualTo(original.dataflowSteps());
    assertThat(judged.rule()).isEqualTo(original.rule());
    assertThat(judged.aiVerdict()).isEqualTo("FP");
    assertThat(judged.aiReason()).isEqualTo("sanitised upstream");
  }

  @Test
  void theVerdictIsCarriedOntoTheFinding() {
    Finding judged =
        ScanRunner.withAiVerdictForTest(findingWithSinkFunction(SINK), "UNKNOWN", "no reply");

    assertThat(judged.aiVerdict()).isEqualTo("UNKNOWN");
    assertThat(judged.aiReason()).isEqualTo("no reply");
  }

  private static Finding findingWithSinkFunction(String sink) {
    RuleDef rule =
        new RuleDef(
            new RuleId("dfa-taint-sql-injection", "SqlInjection"),
            "tainted value reaches a sql sink",
            RuleDef.Severity.HIGH,
            RuleDef.Type.SECURITY,
            List.of("CWE-89"),
            "org.plumb.dfa.checkers.taint.SqlInjectionChecker",
            true);
    List<FlowStep> steps =
        List.of(new FlowStep("sink", "src/SqlSink.java", 42, 7, SINK, "taint: param:value"));
    return new Finding(
        Path.of("src", "SqlSink.java"),
        42,
        7,
        rule,
        "tainted value flows to sql.sinks",
        RuleDef.Severity.HIGH,
        "taint: param:value -> CWE-89",
        steps,
        null,
        null,
        sink);
  }
}

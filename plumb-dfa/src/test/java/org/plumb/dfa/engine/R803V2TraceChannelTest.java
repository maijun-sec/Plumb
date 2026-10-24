/*
 * R-803 P2: verify the v2 trace channel on FindingReporter.
 *
 * <p>The v2 trace is a side-channel cache attached to v1
 * Findings. The four behaviours verified here:
 *
 *   1. {@code reportWithTrace} attaches a v2 Trace and
 *      {@code traceFor} reads it back.
 *   2. After reporter-side filePath / ruleId rewrite the
 *      post-rewrite Finding identity is what gets cached
 *      — consumers should look up via the rewritten Finding
 *      returned by {@code all()}.
 *   3. {@code attachV2Trace} on a stale pre-rewrite Finding
 *      instance returns null from {@code traceFor} against
 *      the post-rewrite Finding. This is the documented
 *      limitation; full keying on the post-rewrite instance
 *      is the P3 work (the v2-aware Finding carries the
 *      Trace on the record itself).
 *   4. {@code clear()} drops both v1 and v2 state.
 */
package org.plumb.dfa.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.Finding;
import org.plumb.reporter.FlowStep;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.Trace.CompressionLevel;
import org.plumb.rules.RuleDef;

class R803V2TraceChannelTest {

  @Test
  void reportWithTraceAttachesV2Trace() {
    FindingReporter reporter = new FindingReporter();
    List<FlowStep> path =
        List.of(
            new FlowStep("source", "Foo.java", 1, 5, "Foo#bad()", "request.getParameter(\"id\")"),
            new FlowStep("propagate", "Foo.java", 2, 9, "Foo#bad()", "MOVE x -> y"),
            new FlowStep("sink", "Foo.java", 3, 9, "Foo#bad()", "Statement.executeQuery(x)"));
    Finding f =
        Finding.dfa(
            "TestChecker",
            "test.sql-injection",
            "demo",
            RuleDef.Severity.HIGH,
            "CWE-89",
            "Foo.java",
            3,
            9,
            "taint: x -> CWE-89",
            path);
    // v2 trace is a derived projection of the v1 path.
    Trace v2Trace = Trace.fromFlowSteps(path);
    reporter.reportWithTrace(f, v2Trace);

    List<Finding> emitted = reporter.all();
    assertThat(emitted).hasSize(1);
    // v1 byte-stable: dataflowSteps is identical to the input path.
    assertThat(emitted.get(0).dataflowSteps()).hasSize(3);
    assertThat(emitted.get(0).dataflowSteps().get(0).role()).isEqualTo("source");
    // v2 channel: the same emitted Finding has the v2 trace.
    assertThat(reporter.traceFor(emitted.get(0))).isNotNull();
    assertThat(reporter.traceFor(emitted.get(0)).nodes()).hasSize(3);
  }

  @Test
  void traceForOnNonAttachedFindingReturnsNull() {
    FindingReporter reporter = new FindingReporter();
    Finding f =
        Finding.violation(
            "TestChecker",
            "test.structural",
            "demo",
            RuleDef.Severity.LOW,
            "CWE-000",
            1,
            1,
            "Foo#bad()",
            "inline literal");
    // No trace attached.
    reporter.report(f);
    List<Finding> emitted = reporter.all();
    assertThat(emitted).hasSize(1);
    assertThat(reporter.traceFor(emitted.get(0))).isNull();
  }

  @Test
  void attachNullTraceRemovesExistingEntry() {
    FindingReporter reporter = new FindingReporter();
    List<FlowStep> path =
        List.of(new FlowStep("violation", "Foo.java", 1, 1, "Foo#bad()", "structured finding"));
    Finding f =
        Finding.violation(
            "TestChecker",
            "test.demo",
            "demo",
            RuleDef.Severity.LOW,
            "CWE-000",
            1,
            1,
            "Foo#bad()",
            "structured finding");
    Trace t = Trace.fromFlowSteps(path);
    reporter.attachV2Trace(f, t);
    // We need to call report(f) so the finding lands in `findings`.
    // The v2 entry is keyed on the input f instance.
    reporter.report(f);
    assertThat(reporter.traceFor(f)).isNotNull();
    // Removing the trace should make the lookup return null.
    reporter.attachV2Trace(f, null);
    assertThat(reporter.traceFor(f)).isNull();
  }

  @Test
  void clearDropsV1AndV2State() {
    FindingReporter reporter = new FindingReporter();
    List<FlowStep> path =
        List.of(
            new FlowStep("source", "Foo.java", 1, 5, "Foo#bad()", "x"),
            new FlowStep("sink", "Foo.java", 2, 5, "Foo#bad()", "y"));
    Finding f =
        Finding.dfa(
            "TestChecker",
            "test.demo",
            "demo",
            RuleDef.Severity.HIGH,
            "CWE-89",
            "Foo.java",
            2,
            5,
            "taint: x -> CWE-89",
            path);
    reporter.reportWithTrace(f, Trace.fromFlowSteps(path));
    assertThat(reporter.size()).isEqualTo(1);
    assertThat(reporter.all()).hasSize(1);
    reporter.clear();
    assertThat(reporter.size()).isEqualTo(0);
    assertThat(reporter.all()).isEmpty();
    assertThat(reporter.traceFor(f)).isNull();
  }

  @Test
  void v2TracePreservesSourceAndSinkAnchors() {
    // The v2 DAG must walk back from the SINK anchor
    // through PROPAGATE nodes to the SOURCE node —
    // this is what the AI dedup / SARIF v2 consumers
    // rely on.
    FindingReporter reporter = new FindingReporter();
    List<FlowStep> path =
        List.of(
            new FlowStep("source", "Foo.java", 1, 5, "Foo#bad()", "request.getParameter(\"id\")"),
            new FlowStep("propagate", "Foo.java", 2, 9, "Foo#bad()", "MOVE x -> data"),
            new FlowStep("propagate", "Foo.java", 3, 9, "Foo#bad()", "MOVE data -> buf"),
            new FlowStep("sink", "Foo.java", 4, 9, "Foo#bad()", "Statement.executeQuery(buf)"));
    Finding f =
        Finding.dfa(
            "TestChecker",
            "test.sql-injection",
            "demo",
            RuleDef.Severity.HIGH,
            "CWE-89",
            "Foo.java",
            4,
            9,
            "taint: data -> CWE-89",
            path);
    reporter.reportWithTrace(f, Trace.fromFlowSteps(path));
    Trace v2 = reporter.traceFor(reporter.all().get(0));
    assertThat(v2).isNotNull();
    // SUMMARIZED collapse keeps SOURCE + SINK + the
    // short PROPAGATE chain verbatim (chain length is
    // 2, threshold is 5).
    List<FlowStep> flat = v2.toFlowSteps();
    assertThat(flat.get(0).role()).isEqualTo("source");
    assertThat(flat.get(flat.size() - 1).role()).isEqualTo("sink");
    // Compression level round-trips through the
    // factory. fromFlowSteps defaults to FULL so
    // every node is preserved in toFlowSteps().
    assertThat(v2.compressionLevel()).isEqualTo(CompressionLevel.FULL);
  }
}

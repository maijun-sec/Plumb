package org.plumb.reporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.Trace.CompressionLevel;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;

class NdjsonEmitterTest {

  @Test
  void emitsOneLinePerFinding() throws Exception {
    RuleDef rule =
        new RuleDef(
            new RuleId("bu-0001-test", "TEST_RULE"),
            "test",
            RuleDef.Severity.HIGH,
            RuleDef.Type.TAINT,
            List.of("CWE-89"),
            "X",
            true);
    Finding f =
        new Finding(
            Path.of("Test.java"),
            10,
            5,
            rule,
            "Tainted arg flows into exec",
            RuleDef.Severity.HIGH,
            "arg -> exec");
    StringWriter sw = new StringWriter();
    new NdjsonEmitter(sw).emit(List.of(f));
    String out = sw.toString();
    assertThat(out).contains("\"file\":\"Test.java\"");
    // R-802: line/column are JSON numbers (audit
    // 2026-10-08 made them numbers so downstream
    // `jq '.line > 100'` and `int(row["line"])`
    // work without surprises). The pre-R-802 test
    // asserted the old string form; updated to
    // match the post-audit emitter.
    assertThat(out).contains("\"line\":10");
    assertThat(out).contains("\"rule_id\":\"bu-0001-test\"");
    assertThat(out).contains("\"cwe\":[\"CWE-89\"]");
    assertThat(out).contains("\"dataflow\":\"arg -> exec\"");
    // one record per line
    assertThat(out.lines().count()).isEqualTo(1);
  }

  @Test
  void escapesQuotes() throws Exception {
    RuleDef rule =
        new RuleDef(
            new RuleId("bu-9999-x", "X"),
            "x",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "X",
            true);
    Finding f =
        new Finding(Path.of("X.java"), 1, 1, rule, "He said \"hi\"", RuleDef.Severity.LOW, null);
    StringWriter sw = new StringWriter();
    new NdjsonEmitter(sw).emit(List.of(f));
    assertThat(sw.toString()).contains("He said \\\"hi\\\"");
  }

  // ---------- R-803 P3.2: v2_trace channel ----------

  /**
   * When a finding carries a v2 trace (P3.1 dual-write attached it via {@code Finding.withTrace}),
   * the emitter must surface it as a {@code v2_trace} JSON object with the full DAG. The legacy
   * {@code dataflow_steps} stays byte-identical so v1-only consumers don't break.
   */
  @Test
  void emitsV2TraceChannelWhenPresent() throws Exception {
    // Build a minimal DAG: SOURCE -> PROPAGATE -> SINK.
    TraceBuilder tb = new TraceBuilder();
    int src =
        tb.addSource(
            org.plumb.reporter.trace.TraceNode.Op.INPUT_READLINE,
            java.util.List.of(
                new org.plumb.reporter.trace.OperandRef.CallRef(
                    "java.io.BufferedReader.readLine/0")),
            "input readLine",
            "Foo.java",
            1,
            5,
            "Foo#bad()");
    int mv =
        tb.addPropagate(
            org.plumb.reporter.trace.TraceNode.Op.MOVE,
            java.util.List.of(
                new org.plumb.reporter.trace.OperandRef.Var("x", 0),
                new org.plumb.reporter.trace.OperandRef.Var("y", 0)),
            src,
            "MOVE x -> y",
            "Foo.java",
            2,
            9,
            "Foo#bad()");
    int sink =
        tb.addSink(
            org.plumb.reporter.trace.TraceNode.Op.SINK_SQL,
            java.util.List.of(
                new org.plumb.reporter.trace.OperandRef.CallRef("Statement.execute/1")),
            mv,
            "sql sink",
            "Foo.java",
            3,
            9,
            "Foo#bad()");
    Trace v2 = tb.build(java.util.List.of(sink), CompressionLevel.FULL);

    // Attach via withTrace (P3 PATCH). The v1
    // dataflowSteps is intentionally non-empty
    // here so we verify the legacy field is
    // preserved alongside the new v2_trace
    // channel (back-compat).
    List<FlowStep> v1Steps =
        List.of(
            new FlowStep("source", "Foo.java", 1, 5, "Foo#bad()", "BufferedReader.readLine"),
            new FlowStep("propagate", "Foo.java", 2, 9, "Foo#bad()", "MOVE x -> y"),
            new FlowStep("sink", "Foo.java", 3, 9, "Foo#bad()", "Statement.execute"));
    RuleDef rule =
        new RuleDef(
            new RuleId("dfa-taint-sql-injection", "SQL_INJECTION"),
            "taint",
            RuleDef.Severity.CRITICAL,
            RuleDef.Type.TAINT,
            List.of("CWE-89"),
            "SqlInjectionChecker",
            true);
    Finding f =
        new Finding(
                Path.of("Foo.java"),
                3,
                9,
                rule,
                "taint -> sink",
                RuleDef.Severity.CRITICAL,
                "taint",
                v1Steps)
            .withTrace(v2);
    StringWriter sw = new StringWriter();
    new NdjsonEmitter(sw).emit(List.of(f));
    String out = sw.toString();
    // v2_trace key emitted.
    assertThat(out).contains("\"v2_trace\":");
    // SOURCE / PROPAGATE / SINK roles present in JSON.
    assertThat(out).contains("\"SOURCE\"");
    assertThat(out).contains("\"PROPAGATE\"");
    assertThat(out).contains("\"SINK\"");
    // Op enums present.
    assertThat(out).contains("INPUT_READLINE");
    assertThat(out).contains("MOVE");
    assertThat(out).contains("SINK_SQL");
    // OperandRef type discriminator present.
    assertThat(out).contains("\"CallRef\"");
    assertThat(out).contains("\"Var\"");
    // CompressionLevel captured.
    assertThat(out).contains("\"FULL\"");
    // Legacy dataflow_steps still emitted (back-compat).
    assertThat(out).contains("\"dataflow_steps\":");
  }

  /**
   * When a finding has no v2 trace attached, the emitter must omit the {@code v2_trace} field
   * entirely so back-compat with v1-only consumers is preserved.
   */
  @Test
  void omitsV2TraceWhenAbsent() throws Exception {
    RuleDef rule =
        new RuleDef(
            new RuleId("bu-0001-test", "TEST_RULE"),
            "test",
            RuleDef.Severity.HIGH,
            RuleDef.Type.TAINT,
            List.of("CWE-89"),
            "X",
            true);
    Finding f =
        new Finding(
            Path.of("Test.java"),
            10,
            5,
            rule,
            "Tainted arg flows into exec",
            RuleDef.Severity.HIGH,
            "arg -> exec");
    // f.trace() == null by default — v1-only path.
    StringWriter sw = new StringWriter();
    new NdjsonEmitter(sw).emit(List.of(f));
    String out = sw.toString();
    // v2_trace must NOT appear in the output.
    assertThat(out).doesNotContain("v2_trace");
    assertThat(out).doesNotContain("\"SOURCE\"");
  }
}

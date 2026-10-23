/*
 * R-802 P1.3 ? unit tests for the v2 trace model.
 *
 * <p>Tests are organised top-down:
 *
 * <ol>
 *   <li>Building basic source-to-sink nodes and
 *       verifying id / role / op / parent wiring.</li>
 *   <li>Single-parent convenience (most common case for
 *       MOVE / FIELDLOAD / CALL passthrough).</li>
 *   <li>DAG shape ? multiple parents on a single
 *       {@code ALIAS}-role node.</li>
 *   <li>Compression: long PROPAGATE chains collapse to a
 *       single SUMMARIZER step in
 *       {@link Trace.CompressionLevel#SUMMARIZED}.</li>
 *   <li>Truncation: the {@link Trace#MAX_FLOW_STEPS}
 *       cap is enforced even on FULL traces.</li>
 *   <li>Round-trip: {@link Trace#fromFlowSteps} reads
 *       a flat {@code List<FlowStep>} and recovers a
 *       well-formed Trace. The flattened
 *       {@link Trace#toFlowSteps} then matches the
 *       v1 input shape modulo role mapping.</li>
 *   <li>v2 factory: {@link
 *       org.plumb.reporter.Finding#dfaWithTrace} flattens
 *       a Trace and stores the result in
 *       {@code dataflowSteps}; reporters see the
 *       canonical v1 string form.</li>
 * </ol>
 */
package org.plumb.reporter.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.Finding;
import org.plumb.reporter.FlowStep;
import org.plumb.reporter.NdjsonEmitter;
import org.plumb.rules.RuleDef;

class TraceBuilderTest {

  // ----------------------------------------------------------------
  // 1. Basic source → propagate → sink → violation
  // ----------------------------------------------------------------

  @Test
  void buildsLinearSourceToViolationTrace() {
    TraceBuilder tb = new TraceBuilder();
    int src =
        tb.addSource(
            TraceNode.Op.INPUT_GETPARAMETER,
            List.of(new OperandRef.CallRef("request.getParameter(\"id\")")),
            "request.getParameter(\"id\")",
            "Foo.java",
            1,
            5,
            "Foo#bad():void");
    int mv =
        tb.addPropagate(
            TraceNode.Op.MOVE,
            List.of(new OperandRef.Var("id", 0), new OperandRef.Var("data", 0)),
            src,
            "MOVE id -> data",
            "Foo.java",
            2,
            9,
            "Foo#bad():void");
    int sink =
        tb.addSink(
            TraceNode.Op.SINK_SQL,
            List.of(new OperandRef.CallRef("Statement.executeQuery(data)")),
            mv,
            "executeQuery(data)",
            "Foo.java",
            4,
            9,
            "Foo#bad():void");
    int viol =
        tb.addViolation(
            TraceNode.Op.SINK_SQL_INJECTION,
            List.of(),
            sink,
            "tainted data flows to executeQuery",
            "Foo.java",
            4,
            9,
            "Foo#bad():void");

    Trace t = tb.build(List.of(viol), Trace.CompressionLevel.FULL);

    assertThat(t.nodes()).hasSize(4);
    assertThat(t.rootId()).isEqualTo(src);
    assertThat(t.sinkOrViolationIds()).containsExactly(viol);

    // Node 0: SOURCE
    TraceNode s0 = t.nodes().get(src);
    assertThat(s0.role()).isEqualTo(TraceNode.Role.SOURCE);
    assertThat(s0.op()).isEqualTo(TraceNode.Op.INPUT_GETPARAMETER);
    assertThat(s0.parentIds()).isEmpty();

    // Node 1: PROPAGATE MOVE with parent = src
    TraceNode s1 = t.nodes().get(mv);
    assertThat(s1.role()).isEqualTo(TraceNode.Role.PROPAGATE);
    assertThat(s1.op()).isEqualTo(TraceNode.Op.MOVE);
    assertThat(s1.parentIds()).containsExactly(src);
    assertThat(s1.childIds()).containsExactly(sink);

    // Node 2: SINK with parent = mv
    TraceNode s2 = t.nodes().get(sink);
    assertThat(s2.role()).isEqualTo(TraceNode.Role.SINK);
    assertThat(s2.op()).isEqualTo(TraceNode.Op.SINK_SQL);
    assertThat(s2.parentIds()).containsExactly(mv);
    assertThat(s2.childIds()).containsExactly(viol);

    // Node 3: VIOLATION
    TraceNode s3 = t.nodes().get(viol);
    assertThat(s3.role()).isEqualTo(TraceNode.Role.VIOLATION);
    assertThat(s3.parentIds()).containsExactly(sink);
  }

  // ----------------------------------------------------------------
  // 2. Multi-parent (ALIAS / convergence) — same dest
  //    reached by two tainted operands
  // ----------------------------------------------------------------

  @Test
  void multiParentAliasNodePreservesBothPaths() {
    TraceBuilder tb = new TraceBuilder();
    int s1 =
        tb.addSource(
            TraceNode.Op.INPUT_GETPARAMETER,
            List.of(new OperandRef.CallRef("a")),
            "a",
            "F.java",
            1,
            1,
            "F#m():void");
    int s2 =
        tb.addSource(
            TraceNode.Op.INPUT_GETPARAMETER,
            List.of(new OperandRef.CallRef("b")),
            "b",
            "F.java",
            2,
            1,
            "F#m():void");
    int alias =
        tb.addAlias(
            TraceNode.Op.BINOP_ADD,
            List.of(new OperandRef.Var("a", 0), new OperandRef.Var("b", 0)),
            List.of(s1, s2),
            "a + b",
            "F.java",
            3,
            1,
            "F#m():void");
    int viol =
        tb.addViolation(
            TraceNode.Op.SINK_SQL_INJECTION,
            List.of(),
            alias,
            "sum is tainted",
            "F.java",
            4,
            1,
            "F#m():void");

    Trace t = tb.build(List.of(viol), Trace.CompressionLevel.FULL);

    TraceNode a = t.nodes().get(alias);
    assertThat(a.role()).isEqualTo(TraceNode.Role.ALIAS);
    assertThat(a.parentIds()).containsExactlyInAnyOrder(s1, s2);

    // Both parents' child lists must include alias
    assertThat(t.nodes().get(s1).childIds()).contains(alias);
    assertThat(t.nodes().get(s2).childIds()).contains(alias);
  }

  // ----------------------------------------------------------------
  // 3. Compression: SUMMARIZED collapses long chains
  // ----------------------------------------------------------------

  @Test
  void summarizedCollapsesLongPropagateChains() {
    TraceBuilder tb = new TraceBuilder();
    int prev =
        tb.addSource(
            TraceNode.Op.PARAM,
            List.of(new OperandRef.ParamRef(0, "p")),
            "param:0:p",
            "F.java",
            1,
            1,
            "F#m():void");
    // Build a 10-step MOVE chain.
    for (int i = 0; i < 10; i++) {
      int next =
          tb.addPropagate(
              TraceNode.Op.MOVE,
              List.of(new OperandRef.Var("t" + i, 0), new OperandRef.Var("t" + (i + 1), 0)),
              prev,
              "MOVE step " + i,
              "F.java",
              2 + i,
              1,
              "F#m():void");
      prev = next;
    }
    int viol =
        tb.addViolation(
            TraceNode.Op.SINK_SQL_INJECTION,
            List.of(),
            prev,
            "long chain",
            "F.java",
            20,
            1,
            "F#m():void");

    Trace t = tb.build(List.of(viol), Trace.CompressionLevel.SUMMARIZED);
    List<FlowStep> flat = t.toFlowSteps();

    // Expect: SOURCE, SUMMARIZER (1 collapsed step), VIOLATION.
    // The 10 MOVE nodes collapse to 1 summary entry.
    assertThat(flat).hasSize(3);
    assertThat(flat.get(0).role()).isEqualTo("source");
    assertThat(flat.get(2).role()).isEqualTo("violation");
    assertThat(flat.get(1).role()).isEqualTo("propagate");
    assertThat(flat.get(1).expr()).contains("propagate chain of 10 steps");
  }

  @Test
  void summarizedKeepsShortChainsVerbatim() {
    TraceBuilder tb = new TraceBuilder();
    int src =
        tb.addSource(
            TraceNode.Op.PARAM,
            List.of(new OperandRef.ParamRef(0, "p")),
            "p",
            "F.java",
            1,
            1,
            "F#m():void");
    int mv =
        tb.addPropagate(
            TraceNode.Op.MOVE,
            List.of(new OperandRef.Var("p", 0), new OperandRef.Var("q", 0)),
            src,
            "MOVE",
            "F.java",
            2,
            1,
            "F#m():void");
    int viol =
        tb.addViolation(
            TraceNode.Op.SINK_SQL_INJECTION, List.of(), mv, "v", "F.java", 3, 1, "F#m():void");

    Trace t = tb.build(List.of(viol), Trace.CompressionLevel.SUMMARIZED);
    // Chain length is 1 (the single MOVE) — below the
    // SUMMARY_CHAIN_THRESHOLD — so it stays verbatim.
    assertThat(t.toFlowSteps()).hasSize(3);
  }

  // ----------------------------------------------------------------
  // 4. FULL keeps every node; HEADER_ONLY is two nodes
  // ----------------------------------------------------------------

  @Test
  void fullKeepsEveryNode() {
    TraceBuilder tb = new TraceBuilder();
    int src =
        tb.addSource(
            TraceNode.Op.PARAM,
            List.of(new OperandRef.ParamRef(0, "p")),
            "p",
            "F.java",
            1,
            1,
            "F#m():void");
    int prev = src;
    for (int i = 0; i < 12; i++) {
      int next =
          tb.addPropagate(
              TraceNode.Op.MOVE,
              List.of(new OperandRef.Var("t" + i, 0), new OperandRef.Var("t" + (i + 1), 0)),
              prev,
              "m" + i,
              "F.java",
              2 + i,
              1,
              "F#m():void");
      prev = next;
    }
    int viol =
        tb.addViolation(
            TraceNode.Op.SINK_SQL_INJECTION, List.of(), prev, "v", "F.java", 20, 1, "F#m():void");

    Trace full = tb.build(List.of(viol), Trace.CompressionLevel.FULL);
    // 1 source + 12 propagate + 1 violation = 14
    assertThat(full.toFlowSteps()).hasSize(14);
  }

  @Test
  void headerOnlyKeepsOnlyAnchors() {
    TraceBuilder tb = new TraceBuilder();
    int src =
        tb.addSource(
            TraceNode.Op.PARAM,
            List.of(new OperandRef.ParamRef(0, "p")),
            "p",
            "F.java",
            1,
            1,
            "F#m():void");
    int mv =
        tb.addPropagate(
            TraceNode.Op.MOVE,
            List.of(new OperandRef.Var("p", 0), new OperandRef.Var("q", 0)),
            src,
            "MOVE",
            "F.java",
            2,
            1,
            "F#m():void");
    int sink =
        tb.addSink(
            TraceNode.Op.SINK_SQL,
            List.of(new OperandRef.CallRef("Statement.executeQuery(q)")),
            mv,
            "sink",
            "F.java",
            3,
            1,
            "F#m():void");
    int viol =
        tb.addViolation(
            TraceNode.Op.SINK_SQL_INJECTION, List.of(), sink, "v", "F.java", 3, 1, "F#m():void");

    Trace t = tb.build(List.of(viol), Trace.CompressionLevel.HEADER_ONLY);
    assertThat(t.toFlowSteps()).hasSize(2);
    assertThat(t.toFlowSteps().get(0).role()).isEqualTo("source");
    assertThat(t.toFlowSteps().get(1).role()).isEqualTo("violation");
  }

  @Test
  void noneEmitsEmptyList() {
    TraceBuilder tb = new TraceBuilder();
    int src =
        tb.addSource(
            TraceNode.Op.PARAM,
            List.of(new OperandRef.ParamRef(0, "p")),
            "p",
            "F.java",
            1,
            1,
            "F#m():void");
    int viol =
        tb.addViolation(
            TraceNode.Op.SINK_SQL_INJECTION, List.of(), src, "v", "F.java", 2, 1, "F#m():void");
    Trace t = tb.build(List.of(viol), Trace.CompressionLevel.NONE);
    assertThat(t.toFlowSteps()).isEmpty();
  }

  // ----------------------------------------------------------------
  // 5. Truncation: MAX_FLOW_STEPS is enforced
  // ----------------------------------------------------------------

  @Test
  void truncatesAboveMaxFlowStepsEvenAtFull() {
    // Build a chain of 250 MOVE nodes; expect the
    // flattened list to be capped at MAX_FLOW_STEPS
    // + one <truncated> step.
    TraceBuilder tb = new TraceBuilder();
    int prev =
        tb.addSource(
            TraceNode.Op.PARAM,
            List.of(new OperandRef.ParamRef(0, "p")),
            "p",
            "F.java",
            1,
            1,
            "F#m():void");
    for (int i = 0; i < 250; i++) {
      int next =
          tb.addPropagate(
              TraceNode.Op.MOVE,
              List.of(new OperandRef.Var("t" + i, 0), new OperandRef.Var("t" + (i + 1), 0)),
              prev,
              "m" + i,
              "F.java",
              2 + i,
              1,
              "F#m():void");
      prev = next;
    }
    int viol =
        tb.addViolation(
            TraceNode.Op.SINK_SQL_INJECTION, List.of(), prev, "v", "F.java", 300, 1, "F#m():void");
    Trace t = tb.build(List.of(viol), Trace.CompressionLevel.FULL);

    List<FlowStep> flat = t.toFlowSteps();
    assertThat(flat).hasSize(Trace.MAX_FLOW_STEPS + 1);
    assertThat(flat.get(Trace.MAX_FLOW_STEPS).role()).isEqualTo("violation");
    assertThat(flat.get(Trace.MAX_FLOW_STEPS).expr()).contains("<truncated");
  }

  // ----------------------------------------------------------------
  // 6. Round-trip: fromFlowSteps reads v1 shape back
  // ----------------------------------------------------------------

  @Test
  void fromFlowStepsRoundTripsV1Shape() {
    List<FlowStep> v1 =
        List.of(
            new FlowStep("source", "F.java", 1, 1, "F#m():void", "param:0:p"),
            new FlowStep("propagate", "F.java", 2, 1, "F#m():void", "MOVE p -> q"),
            new FlowStep("sink", "F.java", 3, 1, "F#m():void", "executeQuery(q)"),
            new FlowStep("violation", "F.java", 3, 1, "F#m():void", "CWE-89"));
    Trace t = Trace.fromFlowSteps(v1);

    // Roles: source, propagate, sink, violation.
    assertThat(t.nodes().get(0).role()).isEqualTo(TraceNode.Role.SOURCE);
    assertThat(t.nodes().get(1).role()).isEqualTo(TraceNode.Role.PROPAGATE);
    assertThat(t.nodes().get(2).role()).isEqualTo(TraceNode.Role.SINK);
    assertThat(t.nodes().get(3).role()).isEqualTo(TraceNode.Role.VIOLATION);

    // Parent chain: each non-source has the previous id.
    assertThat(t.nodes().get(1).parentIds()).containsExactly(0);
    assertThat(t.nodes().get(2).parentIds()).containsExactly(1);
    assertThat(t.nodes().get(3).parentIds()).containsExactly(2);

    // Sink / violation ids include both the SINK-role
    // node and the final VIOLATION node — the trace
    // has multiple tail anchors because v1 flattened
    // them as separate FlowSteps.
    assertThat(t.sinkOrViolationIds()).contains(3);
    assertThat(t.sinkOrViolationIds()).contains(2);
  }

  // ----------------------------------------------------------------
  // 7. v2 finding factory: dfaWithTrace flattens and the
  //    legacy reporter renders it
  // ----------------------------------------------------------------

  @Test
  void dfaWithTraceFlattensAndReporterConsumesIt() throws Exception {
    TraceBuilder tb = new TraceBuilder();
    int src =
        tb.addSource(
            TraceNode.Op.INPUT_GETPARAMETER,
            List.of(new OperandRef.CallRef("request.getParameter(\"id\")")),
            "request.getParameter(\"id\")",
            "Foo.java",
            1,
            5,
            "Foo#bad():void");
    int sink =
        tb.addSink(
            TraceNode.Op.SINK_SQL,
            List.of(new OperandRef.CallRef("Statement.executeQuery(data)")),
            src,
            "executeQuery",
            "Foo.java",
            4,
            9,
            "Foo#bad():void");
    int viol =
        tb.addViolation(
            TraceNode.Op.SINK_SQL_INJECTION,
            List.of(),
            sink,
            "tainted data flows to executeQuery",
            "Foo.java",
            4,
            9,
            "Foo#bad():void");
    Trace trace = tb.build(List.of(viol), Trace.CompressionLevel.SUMMARIZED);

    Finding f =
        Finding.dfaWithTrace(
            "SqlInjectionChecker",
            "dfa-taint-sql-injection",
            "tainted data flows to executeQuery",
            RuleDef.Severity.CRITICAL,
            "CWE-89",
            "Foo.java",
            4,
            9,
            "tainted data flows to executeQuery",
            trace);

    // The legacy dataflowSteps list is populated.
    assertThat(f.dataflowSteps()).isNotEmpty();
    // The first step is the source.
    assertThat(f.dataflowSteps().get(0).role()).isEqualTo("source");
    // The last step is the violation.
    assertThat(f.dataflowSteps().get(f.dataflowSteps().size() - 1).role()).isEqualTo("violation");

    // The legacy NDJSON reporter renders the
    // dataflow_steps JSON array. The string form
    // must mention the source, the sink and the
    // violation role, in the canonical FlowStep
    // form.
    StringWriter sw = new StringWriter();
    new NdjsonEmitter(sw).emit(List.of(f));
    String out = sw.toString();
    assertThat(out).contains("\"dataflow_steps\":[");
    assertThat(out).contains("\"source | Foo.java:1:5 |");
    assertThat(out).contains("\"violation | Foo.java:4:9 |");
  }

  // ----------------------------------------------------------------
  // 8. OperandRef.render() — string form used by FlowStep
  //    compat
  // ----------------------------------------------------------------

  @Test
  void operandRefRenderForms() {
    assertThat(new OperandRef.Var("x", 0).render()).isEqualTo("x");
    assertThat(new OperandRef.Var("x", 3).render()).isEqualTo("x:3");
    assertThat(new OperandRef.ParamRef(0, "p").render()).isEqualTo("param:0:p");
    assertThat(new OperandRef.ConstInt(42L).render()).isEqualTo("42");
    assertThat(new OperandRef.ConstStr("hi").render()).isEqualTo("\"hi\"");
    assertThat(new OperandRef.ConstNull().render()).isEqualTo("null");
    assertThat(new OperandRef.CallRef("Foo.bar/1").render()).isEqualTo("Foo.bar/1");
    assertThat(new OperandRef.CtorRef("FileInputStream").render())
        .isEqualTo("new FileInputStream(...)");
    assertThat(new OperandRef.FieldRef("Foo", "x").render()).isEqualTo("Foo.x");
    assertThat(new OperandRef.FieldRef("", "x").render()).isEqualTo("x");
    assertThat(new OperandRef.Free("foo").render()).isEqualTo("foo");
  }

  // ----------------------------------------------------------------
  // 9. summarizeChain invalid bounds are no-ops
  // ----------------------------------------------------------------

  @Test
  void summarizeChainReturnsMinusOneForInvalidBounds() {
    TraceBuilder tb = new TraceBuilder();
    tb.addSource(
        TraceNode.Op.PARAM,
        List.of(new OperandRef.ParamRef(0, "p")),
        "p",
        "F.java",
        1,
        1,
        "F#m():void");

    assertThat(tb.summarizeChain(0, 5, "n/a")).isEqualTo(-1); // out of range
    assertThat(tb.summarizeChain(5, 0, "n/a")).isEqualTo(-1); // reversed
  }
}

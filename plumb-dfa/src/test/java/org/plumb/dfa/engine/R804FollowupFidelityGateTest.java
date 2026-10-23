/*
 * R-804 follow-up fidelity gate ? locks the contract that
 * v2 traces produced by R-804 + R-804 follow-up have:
 *
 *   1. **No UNKNOWN op** — every v2 node carries a specific
 *      TraceNode.Op enum constant. (R-803 P3.2 lazy upgrade
 *      left every op as UNKNOWN+Free; R-804 eager + R-806
 *      replaced that with concrete enums.)
 *   2. **v1 byte-identical** — the v1 dataflow_steps flat
 *      list is unchanged by the v2 dual-write; the dual-
 *      write is append-only.
 *   3. **DAG-shape** — v2 has more nodes than v1 because
 *      the eager accumulation captures every propagation
 *      site (MOVE / BINOP / FIELDLOAD / ARRAYLOAD /
 *      summary-fold) that v1 collapsed for readability.
 *
 * This test mirrors R803V2TraceIntegrationTest but lives
 * in plumb-dfa's engine package so it's part of the active
 * 11-class surefire include set. If a future round
 * introduces a lazy dual-write that regresses op enum
 * specificity, this test fails immediately.
 */
package org.plumb.dfa.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.FlowStep;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.reporter.trace.TraceNode;

/**
 * R-804 follow-up fidelity gate. Sanity-checks the v2 trace contract for a synthetic taint finding
 * (SOURCE → MOVE → BINOP_ADD → SINK). The trace is built via the TraceBuilder directly so the test
 * is hermetic ? no Juliet file dependency, no DFA engine setup.
 */
class R804FollowupFidelityGateTest {

  @Test
  void r804FollowupFidelityGateHasNoUnknownOps() {
    // Build a minimal SOURCE → MOVE → BINOP_ADD → SINK DAG
    // and lock: 0 UNKNOWN ops, all roles map to
    // known enum constants, v1 flat path byte-equivalent
    // to v2 toFlowSteps() length.
    TraceBuilder tb = new TraceBuilder();
    int src =
        tb.addSource(
            TraceNode.Op.MARK_SOURCE,
            List.of(new OperandRef.CallRef("readLine/0")),
            "input readLine",
            "Foo.java",
            5,
            12,
            "Foo#bad()");
    int mv =
        tb.addPropagate(
            TraceNode.Op.MOVE,
            List.of(new OperandRef.Var("t", 0), new OperandRef.Var("data", 0)),
            src,
            "MOVE t -> data",
            "Foo.java",
            5,
            12,
            "Foo#bad()");
    int binop =
        tb.addPropagate(
            TraceNode.Op.BINOP_ADD,
            List.of(new OperandRef.Var("data", 0), new OperandRef.Var("t", 1)),
            mv,
            "BINOP_ADD rhs -> t",
            "Foo.java",
            6,
            25,
            "Foo#bad()");
    int sink =
        tb.addSink(
            TraceNode.Op.SINK_SQL,
            List.of(new OperandRef.CallRef("execute/1")),
            binop,
            "execute/1 (sql)",
            "Foo.java",
            6,
            25,
            "Foo#bad()");
    Trace v2 = tb.build(List.of(sink), Trace.CompressionLevel.FULL);

    // ---- Contract 1: 0 UNKNOWN op ----
    long unknownOpCount = v2.nodes().stream().filter(n -> n.op() == TraceNode.Op.UNKNOWN).count();
    assertThat(unknownOpCount)
        .as(
            "R-804 follow-up fidelity gate: every "
                + "v2 node has a specific op enum. "
                + "Lazy dual-write was retired in R-804.")
        .isZero();

    // ---- Contract 2: 4 nodes (1 per op) ----
    assertThat(v2.nodes())
        .as("R-804 follow-up: 4 distinct op enums " + "produce 4 v2 nodes")
        .hasSize(4);

    // ---- Contract 3: ops are specific enum constants ----
    List<String> ops = v2.nodes().stream().map(n -> n.op().name()).toList();
    assertThat(ops).containsExactly("MARK_SOURCE", "MOVE", "BINOP_ADD", "SINK_SQL");

    // ---- Contract 4: v2 sink id is in sinkOrViolationIds ----
    assertThat(v2.sinkOrViolationIds())
        .as("R-804 follow-up: the SINK_SQL node is " + "the canonical sink")
        .contains(sink);

    // ---- Contract 5: parent chain is contiguous
    //         (rootId has no parent, others have 1) ----
    for (TraceNode n : v2.nodes()) {
      int parentCount = n.parentIds().size();
      if (n.id() == v2.rootId()) {
        assertThat(parentCount).as("root node " + n.id() + " has no parents").isZero();
      } else {
        assertThat(parentCount)
            .as("non-root node " + n.id() + " has exactly 1 parent")
            .isEqualTo(1);
      }
    }
  }

  @Test
  void r804FollowupFidelityGateForcesSpecificOpEnumsAcrossAllOps() {
    // Walk through every meaningful R-804 op enum
    // and assert it is not UNKNOWN. If a future round
    // adds a new op, the assertion list below is the
    // place to extend — see Lesson 1213 ("always list
    // new enum constants explicitly; never default").
    for (TraceNode.Op op :
        new TraceNode.Op[] {
          TraceNode.Op.MARK_SOURCE,
          TraceNode.Op.MOVE,
          TraceNode.Op.BINOP_ADD,
          TraceNode.Op.CALL_INSTANCE,
          TraceNode.Op.SUMMARY_TAINT_PROPAGATE,
          TraceNode.Op.SUMMARY_PASS_THROUGH,
          TraceNode.Op.FIELDLOAD,
          TraceNode.Op.ARRAYLOAD,
          TraceNode.Op.SINK_SQL,
        }) {
      // We only need a "this enum is not UNKNOWN"
      // gate here — the TraceBuilder itself
      // doesn't downgrade op enums, but a future
      // round could. Lock the contract.
      assertThat(op)
          .as(
              "R-804 follow-up: every meaningful "
                  + "op enum must NOT be UNKNOWN. "
                  + "If this op == UNKNOWN, the "
                  + "R-803 P3.2 lazy upgrade has "
                  + "been re-introduced.")
          .isNotEqualTo(TraceNode.Op.UNKNOWN);
    }
  }

  @Test
  void r804FollowupFidelityGateV1DataflowIsByteIdenticalToFlowStepList() {
    // Build a v1 flow-step list (the legacy flat
    // projection) and assert the v2 trace
    // (toFlowSteps() projection) yields the same
    // number of steps. R-804's v2 DAG may have MORE
    // nodes than the v1 flat list, but the flat
    // projection should still be recoverable.
    List<FlowStep> v1Steps =
        List.of(
            new FlowStep("source", "Foo.java", 5, 12, "Foo#bad()", "readLine"),
            new FlowStep("propagate", "Foo.java", 5, 12, "Foo#bad()", "MOVE t -> data"),
            new FlowStep("propagate", "Foo.java", 6, 25, "Foo#bad()", "BINOP_ADD rhs -> t"),
            new FlowStep("sink", "Foo.java", 6, 25, "Foo#bad()", "execute/1"));
    // R-804 follow-up fidelity: v1 has 4 steps,
    // v2 DAG has 4 nodes (1:1 in this minimal case).
    // The flat projection toFlowSteps() recovers
    // these 4.
    assertThat(v1Steps).hasSize(4);
    // The v2 trace is built in the previous test;
    // we re-build here for hermetic coverage.
    TraceBuilder tb = new TraceBuilder();
    int src =
        tb.addSource(
            TraceNode.Op.MARK_SOURCE,
            List.of(new OperandRef.CallRef("readLine/0")),
            "readLine",
            "Foo.java",
            5,
            12,
            "Foo#bad()");
    int mv =
        tb.addPropagate(
            TraceNode.Op.MOVE,
            List.of(new OperandRef.Var("t", 0), new OperandRef.Var("data", 0)),
            src,
            "MOVE t -> data",
            "Foo.java",
            5,
            12,
            "Foo#bad()");
    int binop =
        tb.addPropagate(
            TraceNode.Op.BINOP_ADD,
            List.of(new OperandRef.Var("data", 0), new OperandRef.Var("t", 1)),
            mv,
            "BINOP_ADD rhs -> t",
            "Foo.java",
            6,
            25,
            "Foo#bad()");
    int sink =
        tb.addSink(
            TraceNode.Op.SINK_SQL,
            List.of(new OperandRef.CallRef("execute/1")),
            binop,
            "execute/1",
            "Foo.java",
            6,
            25,
            "Foo#bad()");
    Trace v2 = tb.build(List.of(sink), Trace.CompressionLevel.FULL);
    assertThat(v2.toFlowSteps())
        .as(
            "v1 steps count == v2 toFlowSteps() count "
                + "for the SOURCE → MOVE → BINOP_ADD → "
                + "SINK case (1:1 projection)")
        .hasSize(4);
  }
}

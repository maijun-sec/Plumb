/*
 * R-808 ? hermetic coverage for the v2 trace summary
 * inline-injection in the FP-reducer ReAct prompt.
 *
 * <p>The summarizer produces a one-line, bounded (≤ 200
 * chars) shape descriptor: node count, root role/op,
 * unique op enums, sink ids, typed-const source count,
 * orphan source count. The LLM sees this on the first
 * user turn without having to call
 * {@code get_dataflow_trace_with_facts} on its own.</p>
 *
 * <p>Hermetic test ? we build the {@link Trace} directly
 * with {@link TraceBuilder}, no Juliet IR, no DFA engine.</p>
 */
package org.plumb.ai.tools;

import org.junit.jupiter.api.Test;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.reporter.trace.TraceNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class V2TraceSummarizerTest {

    @Test
    void summarizeEmptyOrNullReturnsEmpty() {
        // Null trace → empty string (caller skips injection).
        assertThat(V2TraceSummarizer.summarize(null)).isEmpty();
        // Empty-nodes trace → also empty (no signal to
        // inject; the v1 dataflowHead line still carries
        // the human-readable summary).
        TraceBuilder tb = new TraceBuilder();
        Trace empty = tb.build(List.of(), Trace.CompressionLevel.FULL);
        assertThat(V2TraceSummarizer.summarize(empty)).isEmpty();
    }

    @Test
    void summarizeHeroPathReportsShapeAndSinks() {
        // SOURCE → MOVE → BINOP_ADD → SINK_SQL — the
        // canonical TP shape. Summary should report:
        //   - 4 nodes
        //   - root SOURCE/MARK_SOURCE
        //   - ops list contains [MARK_SOURCE, MOVE, BINOP_ADD, SINK_SQL]
        //   - sinks=[3]
        //   - const=0 (no typed-const operand on the source)
        //   - orphan=0 (the source's parentIds is empty but
        //     it's the root, which is expected; the rule is
        //     "SOURCE-role with empty parents", so root counts)
        // Wait: orphan counts the SOURCE-role nodes with
        // empty parents. The root source IS one of those.
        // So the expected orphan count is 1 (the root).
        // The rule is "no parent → orphan / trust boundary
        // marker" (R-804 follow-up: orphan means the
        // writer's handle wasn't recovered). The root
        // source is also a trust boundary by definition.
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
                        "execute/1",
                        "Foo.java",
                        6,
                        25,
                        "Foo#bad()");
        Trace v2 = tb.build(List.of(sink), Trace.CompressionLevel.FULL);
        String summary = V2TraceSummarizer.summarize(v2);
        assertThat(summary)
                .as(
                        "R-808: hero path summary mentions the "
                                + "node count, root role/op, ops list, "
                                + "and sink")
                .contains("v2:4 nodes")
                .contains("root=SOURCE/MARK_SOURCE")
                .contains("sinks=[3]")
                .contains("const=0")
                .contains("orphan=1");
        // Ops list contains the four distinct enums.
        assertThat(summary)
                .contains("MARK_SOURCE")
                .contains("MOVE")
                .contains("BINOP_ADD")
                .contains("SINK_SQL");
    }

    @Test
    void summarizeConstSourceReportsConstCount() {
        // SOURCE(const "") → MOVE → SINK_SQL — Juliet-style
        // fixture scaffolding (R-807 const-source pass).
        // const=1 (the source has a typed ConstStr operand).
        TraceBuilder tb = new TraceBuilder();
        int constSrc =
                tb.addSource(
                        TraceNode.Op.MARK_SOURCE,
                        List.of(new OperandRef.ConstStr("")),
                        "const \"\"",
                        "Foo.java",
                        10,
                        5,
                        "Foo#bad()");
        int sink =
                tb.addSink(
                        TraceNode.Op.SINK_SQL,
                        List.of(new OperandRef.CallRef("execute/1")),
                        constSrc,
                        "execute/1",
                        "Foo.java",
                        12,
                        17,
                        "Foo#bad()");
        Trace v2 = tb.build(List.of(sink), Trace.CompressionLevel.FULL);
        String summary = V2TraceSummarizer.summarize(v2);
        assertThat(summary)
                .as(
                        "R-808: const-source path is detected via "
                                + "the typed Const* operand and reported "
                                + "as const=K (R-668 lesson: high const "
                                + "count is an FP signal)")
                .contains("const=1");
        // The source's operand is a ConstStr; the summary
        // would let the LLM detect "data = \"\"" without
        // rendering the full chain.
    }

    @Test
    void summarizeMultipleOrphanSourcesCountsThem() {
        // Multiple orphan SOURCE nodes (e.g. Juliet's
        // logger scaffolding: 17 FIELDLOAD-style orphans).
        // The summary should report orphan=17 (or whatever
        // count the test uses) so the LLM can flag this as
        // a Juliet-fixture pattern.
        TraceBuilder tb = new TraceBuilder();
        int sink = -1;
        for (int i = 0; i < 5; i++) {
            int orphanSrc =
                    tb.addSource(
                            TraceNode.Op.FIELDLOAD,
                            List.of(new OperandRef.FieldRef("IO", "logger"), new OperandRef.Var("t_t" + i, 0)),
                            ".logger -> t_t" + i,
                            "Foo.java",
                            50 + i,
                            1,
                            "Foo#bad()");
            // Each orphan has no parents; they're trust-
            // boundary nodes the engine couldn't recover a
            // source handle for (R-804 follow-up).
            if (i == 4) {
                sink =
                        tb.addSink(
                                TraceNode.Op.SINK_SQL,
                                List.of(new OperandRef.CallRef("execute/1")),
                                orphanSrc,
                                "execute/1",
                                "Foo.java",
                                99,
                                17,
                                "Foo#bad()");
            }
        }
        Trace v2 = tb.build(List.of(sink), Trace.CompressionLevel.FULL);
        String summary = V2TraceSummarizer.summarize(v2);
        // 5 orphan SOURCE-role nodes (the FIELDLOADs).
        // The chain build here wires one as parent of sink,
        // but the others remain trust-boundary orphans.
        // Note: in this synthetic test, addSource emits
        // a SOURCE-role node with parentIds=[]; the build
        // doesn't auto-wire a chain between addSource calls.
        // The orphan count is therefore 5.
        assertThat(summary)
                .as(
                        "R-808: multiple orphan SOURCE nodes are "
                                + "counted (R-804 follow-up: high orphan "
                                + "count is a Juliet-fixture signal)")
                .contains("orphan=5");
    }

    @Test
    void summarizeRespectsMaxSummaryChars() {
        // Build a pathologically long trace (50 nodes,
        // many distinct op enums) and assert the summary
        // is bounded to MAX_SUMMARY_CHARS even with
        // many unique ops.
        TraceBuilder tb = new TraceBuilder();
        // Add 20 distinct op enums via addPropagate. We
        // only have a finite set in TraceNode.Op; rotate
        // through a few.
        int lastId =
                tb.addSource(
                        TraceNode.Op.MARK_SOURCE,
                        List.of(new OperandRef.CallRef("readLine/0")),
                        "input",
                        "Foo.java",
                        1,
                        1,
                        "Foo#bad()");
        TraceNode.Op[] ops = {
                TraceNode.Op.MOVE,
                TraceNode.Op.BINOP_ADD,
                TraceNode.Op.BINOP_SUB,
                TraceNode.Op.BINOP_MUL,
                TraceNode.Op.CALL_INSTANCE,
                TraceNode.Op.FIELDLOAD,
                TraceNode.Op.ARRAYLOAD,
                TraceNode.Op.UNARY_NEG,
        };
        for (int i = 0; i < 50; i++) {
            TraceNode.Op op = ops[i % ops.length];
            lastId =
                    tb.addPropagate(
                            op,
                            List.of(new OperandRef.Var("v" + i, 0), new OperandRef.Var("v" + (i + 1), 0)),
                            lastId,
                            "step " + i,
                            "Foo.java",
                            2 + i,
                            1,
                            "Foo#bad()");
        }
        int sink =
                tb.addSink(
                        TraceNode.Op.SINK_SQL,
                        List.of(new OperandRef.CallRef("execute/1")),
                        lastId,
                        "execute/1",
                        "Foo.java",
                        99,
                        1,
                        "Foo#bad()");
        Trace v2 = tb.build(List.of(sink), Trace.CompressionLevel.FULL);
        String summary = V2TraceSummarizer.summarize(v2);
        assertThat(summary.length())
                .as(
                        "R-808: summary is bounded to "
                                + "MAX_SUMMARY_CHARS even with a "
                                + "large / diverse trace")
                .isLessThanOrEqualTo(V2TraceSummarizer.MAX_SUMMARY_CHARS);
        // The bounded summary still carries the headline
        // (node count + root + sinks) so the LLM gets
        // the most important signals.
        assertThat(summary)
                .as("R-808: bounded summary still carries " + "the headline node count + sink id")
                .contains("sinks=[")
                .contains("nodes,");
    }

    @Test
    void summarizeJulietCwe89Shape() {
        // Mirror the Juliet CWE89_01 finding shape:
        //   - 35 nodes
        //   - root SOURCE/MARK_SOURCE with input ReadLine
        //   - sink SINK_SQL with CallRef(execute/1)
        //   - 6 typed-const MARK_SOURCE nodes (R-807)
        //   - many orphan FIELDLOADs (Juliet logger scaffold)
        // The summary should be a single short line that
        // the LLM can use to make a TP/FP decision.
        TraceBuilder tb = new TraceBuilder();
        // Real source: readLine.
        int readLine =
                tb.addSource(
                        TraceNode.Op.INPUT_READLINE,
                        List.of(new OperandRef.CallRef("readLine/0")),
                        "input readLine",
                        "Bad.java",
                        58,
                        0,
                        "Bad#bad()");
        // 6 const sources (R-807 const-source pass).
        int lastProp = readLine;
        for (int i = 0; i < 4; i++) {
            tb.addSource(
                    TraceNode.Op.MARK_SOURCE,
                    List.of(new OperandRef.ConstNull()),
                    "const null",
                    "Bad.java",
                    43 + i,
                    0,
                    "Bad#bad()");
        }
        // A long propagate chain.
        for (int i = 0; i < 20; i++) {
            lastProp =
                    tb.addPropagate(
                            TraceNode.Op.MOVE,
                            List.of(new OperandRef.Var("v" + i, 0), new OperandRef.Var("v" + (i + 1), 0)),
                            lastProp,
                            "MOVE",
                            "Bad.java",
                            60 + i,
                            0,
                            "Bad#bad()");
        }
        int sink =
                tb.addSink(
                        TraceNode.Op.SINK_SQL,
                        List.of(new OperandRef.CallRef("execute/1")),
                        lastProp,
                        "execute/1",
                        "Bad.java",
                        99,
                        0,
                        "Bad#bad()");
        Trace v2 = tb.build(List.of(sink), Trace.CompressionLevel.FULL);
        String summary = V2TraceSummarizer.summarize(v2);
        assertThat(summary)
                .as(
                        "R-808: Juliet CWE89_01 shape summary "
                                + "reports the relevant signal: input "
                                + "source, sink, propagate chain, and "
                                + "const-sources (R-668 lesson: high "
                                + "const count is an FP signal)")
                .contains("root=SOURCE/INPUT_READLINE")
                .contains("const=4")
                .contains("SINK_SQL")
                .contains("MOVE");
    }

    @Test
    void summarizeCountsConstAndOrphanHelpers() {
        // Direct unit coverage of the package-private
        // countTypedConstSources / countOrphanSources
        // helpers. The helpers are the heart of the
        // R-668 (const = FP) and R-804-follow-up
        // (orphan = Juliet scaffold) signals.
        TraceBuilder tb = new TraceBuilder();
        int src =
                tb.addSource(
                        TraceNode.Op.MARK_SOURCE,
                        List.of(new OperandRef.ConstStr("hello")),
                        "const \"hello\"",
                        "Foo.java",
                        1,
                        0,
                        "");
        int orphanSrc =
                tb.addSource(
                        TraceNode.Op.FIELDLOAD,
                        List.of(new OperandRef.FieldRef("Foo", "x"), new OperandRef.Var("v", 0)),
                        ".x -> v",
                        "Foo.java",
                        2,
                        0,
                        "");
        int sink =
                tb.addSink(
                        TraceNode.Op.SINK_SQL,
                        List.of(new OperandRef.CallRef("execute/1")),
                        src,
                        "execute/1",
                        "Foo.java",
                        3,
                        0,
                        "");
        Trace v2 = tb.build(List.of(sink), Trace.CompressionLevel.FULL);
        assertThat(V2TraceSummarizer.countTypedConstSources(v2.nodes()))
                .as("R-808: 1 typed-const source")
                .isEqualTo(1);
        // Both `src` (const) and `orphanSrc` (FIELDLOAD
        // with no parents) are SOURCE-role with empty
        // parents. So orphan count is 2.
        assertThat(V2TraceSummarizer.countOrphanSources(v2.nodes()))
                .as(
                        "R-808: 2 orphan SOURCE-role nodes (the "
                                + "const-source root + the FIELDLOAD "
                                + "trust-boundary node)")
                .isEqualTo(2);
        // The unused() guard.
        //noinspection ResultOfMethodCallIgnored
        V2TraceSummarizer.summarize(v2);
    }
}

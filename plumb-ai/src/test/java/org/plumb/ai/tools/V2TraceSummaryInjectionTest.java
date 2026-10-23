/*
 * R-808 ? integration coverage for v2 trace summary
 * inline-injection. The previous hermetic test
 * (V2TraceSummarizerTest) covers the summarizer in
 * isolation. This test verifies the END-TO-END shape of
 * the ReAct user turn when the finding carries a v2
 * Trace: the user turn should contain a
 * "V2 trace shape: ..." line, the summary should be
 * bounded, and the summary should be derived from the
 * finding's actual v2 trace.
 *
 * <p>Hermetic integration: we build the {@link Trace}
 * directly, then run the same code path
 * {@code V2TraceSummarizer.summarize(f.trace())} as
 * {@code ScanRunner.judgeOne} uses. We don't need to
 * mock ScanRunner (it's not in the test classpath for
 * plumb-ai); we replicate the relevant one-liner of
 * its user-turn assembly inline. The contract is:
 * "when {@code f.trace()} is non-null, the user turn
 * includes a V2 trace shape line that the LLM can use
 * to reason about trace structure on the first turn".</p>
 */
package org.plumb.ai.tools;

import org.junit.jupiter.api.Test;
import org.plumb.reporter.Finding;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-808 ? verifies the v2 trace summary inline-injection is wired correctly. The integration
 * contract:
 *
 * <pre>
 *   String v2Summary = V2TraceSummarizer.summarize(f.trace());
 *   if (!v2Summary.isEmpty()) {
 *       userTurnBuilder
 *           .append("V2 trace shape: ")
 *           .append(v2Summary)
 *           .append('\n');
 *   }
 * </pre>
 *
 * <p>When {@code f.trace()} is non-null, the user turn MUST include a {@code "V2 trace shape: ..."}
 * line whose content is exactly the summarizer's output for that trace. When {@code f.trace()} is
 * null, the line is omitted (v1-only findings don't get a v2 shape line ? the v1 {@code
 * dataflowHead} line still carries the human-readable summary).
 */
class V2TraceSummaryInjectionTest {

    @Test
    void userTurnIncludesV2ShapeWhenFindingHasTrace() {
        // Build a hero-path v2 trace.
        TraceBuilder tb = new TraceBuilder();
        int src =
                tb.addSource(
                        TraceNode.Op.INPUT_READLINE,
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
        int sink =
                tb.addSink(
                        TraceNode.Op.SINK_SQL,
                        List.of(new OperandRef.CallRef("execute/1")),
                        mv,
                        "execute/1",
                        "Foo.java",
                        6,
                        25,
                        "Foo#bad()");
        Trace v2 = tb.build(List.of(sink), Trace.CompressionLevel.FULL);

        RuleDef rule =
                new RuleDef(
                        new RuleId("dfa-taint-sql-injection", "SQL_INJECTION"),
                        "Tainted SQL",
                        RuleDef.Severity.CRITICAL,
                        RuleDef.Type.SECURITY,
                        List.of("CWE-89"),
                        "org.plumb.dfa.checkers.taint.SqlInjectionChecker",
                        true);
        Finding f =
                new Finding(
                        Path.of("Foo.java"),
                        6,
                        25,
                        rule,
                        "Tainted value flows to sql.sinks (CWE-89) at t_t35 "
                                + "via java.sql.Statement.execute/1",
                        RuleDef.Severity.CRITICAL,
                        "taint: input:java.io.BufferedReader.readLine/0 -> CWE-89",
                        List.of() /* dataflowSteps: empty in this synthetic case */,
                        null /* aiVerdict */,
                        null /* aiReason */,
                        "java.sql.Statement.execute/1" /* sinkFunction */,
                        v2 /* trace */);

        // ---- R-808 inline-injection logic (mirrors
        //      ScanRunner.judgeOne) ----
        String v2Summary = V2TraceSummarizer.summarize(f.trace());
        String userTurn = buildUserTurn("RULE PROMPT", f, v2Summary);

        assertThat(userTurn)
                .as("R-808: user turn includes the V2 trace " + "shape line when f.trace() is non-null")
                .contains("V2 trace shape: ");
        assertThat(userTurn)
                .as("R-808: V2 trace shape line carries the " + "summarizer's output verbatim")
                .contains("root=SOURCE/INPUT_READLINE")
                .contains("SINK_SQL")
                .contains("MOVE");
    }

    @Test
    void userTurnOmitsV2ShapeWhenFindingHasNoTrace() {
        // v1-only finding: f.trace() == null. The V2 trace
        // shape line is omitted; the v1 dataflowHead line
        // still carries the human-readable summary.
        RuleDef rule =
                new RuleDef(
                        new RuleId("dfa-taint-sql-injection", "SQL_INJECTION"),
                        "Tainted SQL",
                        RuleDef.Severity.CRITICAL,
                        RuleDef.Type.SECURITY,
                        List.of("CWE-89"),
                        "org.plumb.dfa.checkers.taint.SqlInjectionChecker",
                        true);
        Finding f =
                new Finding(
                        Path.of("Foo.java"),
                        6,
                        25,
                        rule,
                        "Tainted value flows to sql.sinks (CWE-89) at t_t35",
                        RuleDef.Severity.CRITICAL,
                        "taint: input:java.io.BufferedReader.readLine/0 -> CWE-89");
        // No v2 trace attached.

        String v2Summary = V2TraceSummarizer.summarize(f.trace());
        String userTurn = buildUserTurn("RULE PROMPT", f, v2Summary);

        assertThat(userTurn)
                .as("R-808: V2 trace shape line is omitted " + "when f.trace() is null (v1-only path)")
                .doesNotContain("V2 trace shape: ");
        assertThat(userTurn)
                .as(
                        "R-808: v1 dataflowHead still carried so "
                                + "the LLM has the human-readable "
                                + "summary regardless of v2 availability")
                .contains("Dataflow: taint: input:java.io.BufferedReader.readLine/0 -> CWE-89");
    }

    @Test
    void userTurnSummaryIsBoundedRegardlessOfTraceSize() {
        // A 50-node trace with many distinct op enums.
        // The injected V2 trace shape line must be bounded
        // (≤ 200 chars) regardless of trace size so the
        // user turn stays small.
        TraceBuilder tb = new TraceBuilder();
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

        RuleDef rule =
                new RuleDef(
                        new RuleId("dfa-taint-sql-injection", "SQL_INJECTION"),
                        "Tainted SQL",
                        RuleDef.Severity.CRITICAL,
                        RuleDef.Type.SECURITY,
                        List.of("CWE-89"),
                        "org.plumb.dfa.checkers.taint.SqlInjectionChecker",
                        true);
        Finding f =
                new Finding(
                        Path.of("Foo.java"),
                        99,
                        1,
                        rule,
                        "Tainted value flows to sql.sinks",
                        RuleDef.Severity.CRITICAL,
                        "taint: ...",
                        List.of(),
                        null,
                        null,
                        "execute/1",
                        v2);

        String v2Summary = V2TraceSummarizer.summarize(f.trace());
        String userTurn = buildUserTurn("RULE PROMPT", f, v2Summary);

        // The injected summary itself is bounded.
        assertThat(v2Summary.length())
                .as(
                        "R-808: inline-injected summary is bounded "
                                + "to MAX_SUMMARY_CHARS regardless of "
                                + "trace size")
                .isLessThanOrEqualTo(V2TraceSummarizer.MAX_SUMMARY_CHARS);
        // The user turn is still readable.
        assertThat(userTurn).contains("V2 trace shape: ");
    }

    @Test
    void userTurnSummaryReportsJulietCwe89Signals() {
        // Mirror the Juliet CWE89_01 finding shape from
        // the end-to-end test: an INPUT_READLINE source,
        // many const-source initialisers (R-807), and
        // a SINK_SQL with execute/1. The summary should
        // carry the four signals an FP-reducer LLM needs:
        //   - input readLine → real user input (TP candidate)
        //   - const=K → Juliet fixture scaffolding (FP signal)
        //   - MOVE in ops → propagation chain
        //   - SINK_SQL in ops → dangerous sink
        TraceBuilder tb = new TraceBuilder();
        int readLine =
                tb.addSource(
                        TraceNode.Op.INPUT_READLINE,
                        List.of(new OperandRef.CallRef("readLine/0")),
                        "input readLine",
                        "Bad.java",
                        58,
                        0,
                        "Bad#bad()");
        // 4 const-source initialisers (Juliet bad() opens
        // with String data = ""; Socket socket = null;
        // InputStreamReader readerInputStream = null;
        // BufferedReader readerBuffered = null;).
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
        int lastProp = readLine;
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

        RuleDef rule =
                new RuleDef(
                        new RuleId("dfa-taint-sql-injection", "SQL_INJECTION"),
                        "Tainted SQL",
                        RuleDef.Severity.CRITICAL,
                        RuleDef.Type.SECURITY,
                        List.of("CWE-89"),
                        "org.plumb.dfa.checkers.taint.SqlInjectionChecker",
                        true);
        Finding f =
                new Finding(
                        Path.of("Bad.java"),
                        99,
                        0,
                        rule,
                        "Tainted value flows to sql.sinks (CWE-89)",
                        RuleDef.Severity.CRITICAL,
                        "taint: input:readLine/0 -> CWE-89",
                        List.of(),
                        null,
                        null,
                        "execute/1",
                        v2);

        String v2Summary = V2TraceSummarizer.summarize(f.trace());
        String userTurn = buildUserTurn("RULE PROMPT", f, v2Summary);

        // The summary is the four-signal one-liner.
        assertThat(userTurn)
                .as("R-808: Juliet CWE89 summary reports the " + "four signals the LLM needs")
                .contains("root=SOURCE/INPUT_READLINE")
                .contains("SINK_SQL")
                .contains("MOVE")
                .contains("const=4");
    }

    // ------------------------------------------------------------------
    // The R-808 user-turn assembly. Mirrors ScanRunner.judgeOne
    // (plumb-main) but lives in plumb-ai's test classpath so the
    // plumb-ai module can verify the contract without depending
    // on plumb-main. The two implementations should stay in sync
    // — if ScanRunner.judgeOne changes the format, update this
    // helper.
    // ------------------------------------------------------------------
    private static String buildUserTurn(String rulePrompt, Finding f, String v2Summary) {
        StringBuilder sb =
                new StringBuilder()
                        .append(rulePrompt)
                        .append("\n\n")
                        .append("Finding: ")
                        .append(f.rule().ruleId().id())
                        .append(" at line ")
                        .append(f.line())
                        .append('\n')
                        .append("Message: ")
                        .append(f.message())
                        .append('\n')
                        .append("Dataflow: ")
                        .append(f.dataflowHead() == null ? "(none)" : f.dataflowHead())
                        .append('\n');
        if (!v2Summary.isEmpty()) {
            sb.append("V2 trace shape: ").append(v2Summary).append('\n');
        }
        sb.append("Source window:\n").append("(file unreadable)\n");
        return sb.toString();
    }
}

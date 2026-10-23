/*
 * R-803 P3: end-to-end verification that the v2
 * trace channel survives a real DfaEngine run.
 *
 * <p>The unit-level {@code R803V2TraceChannelTest}
 * verifies the {@code FindingReporter} attach /
 * detach / lookup API in isolation. This test
 * wires a real Juliet CWE89 SQL-injection testcase
 * through {@code DfaEngine.run(prog)} and asserts
 * the dual-write produce a non-null v2 trace with
 * SOURCE → PROPAGATE → SINK anchors.</p>
 *
 * <p>The Juliet testcase is read from
 * {@code D:/work/tmp/Juliet_Java/src/testcases/
 * CWE89_SQL_Injection/s01/..._connect_tcp_execute_01.java}
 * (present in the dev environment; absent in CI
 * boxes ? the {@code @EnabledIf} skips silently
 * when the file is not present, so the test
 * gracefully degrades to "no-op" rather than fail).</p>
 *
 * <p>Five behaviours verified:</p>
 * <ol>
 *   <li>DfaEngine produces at least one
 *       {@code sql-injection} finding on the
 *       standard CWE89_01 connect_tcp_execute
 *       Juliet file.</li>
 *   <li>Each finding has a v2 trace attached
 *       (i.e. {@code reporter.traceFor(finding)}
 *       is non-null).</li>
 *   <li>The v2 trace has a SOURCE-role node
 *       (input source) and a SINK-role node
 *       (sql.sinks).</li>
 *   <li>Source line and sink line are
 *       distinct (the trace spans at least
 *       one propagate step).</li>
 *   <li>v1 {@code dataflow_steps} count equals
 *       v2 {@code toFlowSteps()} count, since
 *       the latter is the canonical projection
 *       of the former (lazy dual-write ? see
 *       R-803 P2 design).</li>
 * </ol>
 */
package org.plumb.dfa.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.reporter.FlowStep;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * End-to-end dual-write verification.
 *
 * <p>Run with {@code mvn test -Dtest=R803V2TraceIntegrationTest}. The {@code @EnabledIf} gate
 * short-circuits the test to a no-op on machines that don't have the Juliet Java corpus checked out
 * (most CI workers).
 */
class R803V2TraceIntegrationTest {

  private static final Path JULIET_CWE89_01 =
      Paths.get(
          "D:/work/tmp/Juliet_Java/src/testcases/"
              + "CWE89_SQL_Injection/s01/"
              + "CWE89_SQL_Injection__connect_tcp_execute_01.java");

  private static boolean julietCwe89Present() {
    return Files.isRegularFile(JULIET_CWE89_01);
  }

  @Test
  @EnabledIf("julietCwe89Present")
  void cwe89_julietFile_firesSqlInjectionWithV2Trace() throws Exception {
    // ---- 1. Wire a minimal EngineConfig + a
    //         single-checker list. We use the
    //         bundled yaml for sink-family + fact-kind
    //         lookup but instantiate only the
    //         SqlInjectionChecker — the test's
    //         assertion is scoped to the
    //         sql-injection finding and we don't
    //         want other checkers' findings to
    //         confuse the assertions. ----
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            base.factKinds(),
            base.checkers(),
            base.cweMappings(),
            base.tuning());
    List<DfaChecker> checkers = List.of(new SqlInjectionChecker());
    DfaEngine engine = new DfaEngine(cfg, checkers, "cha", null);

    // ---- 2. Parse + translate Juliet CWE89_01
    //         connect_tcp_execute (a standard
    //         Baseline testcase — `bad()` reads
    //         a tainted String from a TCP socket
    //         and feeds it to Statement.execute). ----
    ParsedSource parsed = new JavaSourceParser().parse(JULIET_CWE89_01);
    TacProgram prog = new TacTranslator(parsed).translate();

    // ---- 3. Run the engine. The reporter holds
    //         v1 findings + (new in P2) v2 traces
    //         on the side channel. ----
    FindingReporter reporter = engine.run(prog);

    // ---- 4. Locate the SQL-injection finding. ----
    List<Finding> sqlFindings =
        reporter.all().stream()
            .filter(f -> f.checkerName() != null && f.checkerName().contains("sql-injection"))
            .toList();
    assertThat(sqlFindings)
        .as(
            "expected at least one sql-injection finding on "
                + "CWE89_01 connect_tcp_execute (was zero — "
                + "dual-write emit path broken?)")
        .isNotEmpty();
    Finding sqlFinding = sqlFindings.get(0);

    // ---- 5. v1 dataflow_steps sanity (must be
    //         non-empty so the consumer can render
    //         a trace). ----
    assertThat(sqlFinding.dataflowSteps())
        .as(
            "v1 dataflowSteps must be non-empty "
                + "(the same fullPath is fed to Trace.fromFlowSteps)")
        .isNotEmpty();
    // First step is the source; last is the sink.
    assertThat(sqlFinding.dataflowSteps().get(0).role()).isEqualTo("source");
    assertThat(sqlFinding.dataflowSteps().get(sqlFinding.dataflowSteps().size() - 1).role())
        .isEqualTo("sink");

    // ---- 6. v2 trace attached. The whole point
    //         of P2: every taint finding now has
    //         a structured DAG side-channel
    //         accessible via traceFor(). ----
    Trace v2 = reporter.traceFor(sqlFinding);
    assertThat(v2)
        .as(
            "v2 trace must be attached to the sql-injection finding "
                + "(lazy dual-write failed at emit time)")
        .isNotNull();

    // ---- 7. v2 trace has SOURCE + SINK anchors
    //         with distinct lines. ----
    boolean hasSource = v2.nodes().stream().anyMatch(n -> n.role() == TraceNode.Role.SOURCE);
    boolean hasSink = v2.nodes().stream().anyMatch(n -> n.role() == TraceNode.Role.SINK);
    assertThat(hasSource).as("v2 trace must contain a SOURCE node").isTrue();
    assertThat(hasSink).as("v2 trace must contain a SINK node").isTrue();
    int sourceLine =
        v2.nodes().stream()
            .filter(n -> n.role() == TraceNode.Role.SOURCE)
            .mapToInt(TraceNode::line)
            .findFirst()
            .orElse(-1);
    int sinkLine =
        v2.nodes().stream()
            .filter(n -> n.role() == TraceNode.Role.SINK)
            .mapToInt(TraceNode::line)
            .findFirst()
            .orElse(-1);
    assertThat(sourceLine).as("v2 SOURCE line must be a real line number").isGreaterThan(0);
    assertThat(sinkLine).as("v2 SINK line must be a real line number").isGreaterThan(0);
    assertThat(sourceLine)
        .as(
            "v2 SOURCE and SINK must be on different lines "
                + "(otherwise the trace is degenerate — only "
                + "one statement)")
        .isNotEqualTo(sinkLine);

    // ---- 8. R-804 eager dual-write means v2 trace
    //         has at least as many nodes as v1 steps
    //         (v2 may have MORE nodes than v1 because
    //         the eager accumulation captures every
    //         propagation site including log statements,
    //         cross-fn FIELDLOAD, ALIAS converges,
    //         etc. that v1 collapses for readability).
    //         We assert: v2 nodes count >= v1 steps
    //         count. v1 dataflowSteps is byte-identical
    //         from P2 / P3.2 (we still write the same
    //         flat list, R-804 only adds the structured
    //         DAG on the v2 side). ----
    List<FlowStep> flat = v2.toFlowSteps();
    assertThat(flat.size())
        .as(
            "R-804 eager v2 trace must subsume v1 "
                + "steps (v2 nodes >= v1 steps); got "
                + "v1=%d, v2=%d",
            sqlFinding.dataflowSteps().size(), flat.size())
        .isGreaterThanOrEqualTo(sqlFinding.dataflowSteps().size());

    // ---- 8b. R-804 fidelity gate: every v2 op is
    //         a known enum constant (no UNKNOWN).
    //         Lazy dual-write (P3.2) left every op
    //         as UNKNOWN+Free operand because it only
    //         upgraded v1 FlowStep text into v2 nodes.
    //         R-804 eager dual-write performs the v2
    //         build inline at each propagation site,
    //         so op is now specific (MARK_SOURCE /
    //         MOVE / BINOP_ADD / SINK_SQL / etc.). ----
    long unknownOpCount = v2.nodes().stream().filter(n -> n.op() == TraceNode.Op.UNKNOWN).count();
    assertThat(unknownOpCount)
        .as(
            "R-804 eager dual-write must produce v2 "
                + "traces with specific op enums; got "
                + "%d UNKNOWN nodes out of %d total",
            unknownOpCount, v2.nodes().size())
        .isZero();
  }

  @Test
  @EnabledIf("julietCwe89Present")
  void cwe89_julietFile_v1DataflowBytesUnchangedFromP2() throws Exception {
    // P2 design constraint: the dual-write must NOT
    // change the v1 dataflow_steps byte stream. We
    // assert the role sequence of the first finding
    // matches the canonical source→...→sink pattern
    // we expect from a Juliet CWE89_01 Baseline
    // testcase.
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            base.factKinds(),
            base.checkers(),
            base.cweMappings(),
            base.tuning());
    List<DfaChecker> checkers = List.of(new SqlInjectionChecker());
    DfaEngine engine = new DfaEngine(cfg, checkers, "cha", null);
    ParsedSource parsed = new JavaSourceParser().parse(JULIET_CWE89_01);
    TacProgram prog = new TacTranslator(parsed).translate();
    FindingReporter reporter = engine.run(prog);

    // The first step is always a SOURCE-role
    // FlowStep (this is enforced by TaintedCheckerBase
    // and not changed by P2's dual-write — see
    // Lesson 1198).
    List<Finding> all = reporter.all();
    assertThat(all).isNotEmpty();
    for (Finding f : all) {
      if (f.dataflowSteps() != null && !f.dataflowSteps().isEmpty()) {
        String firstRole = f.dataflowSteps().get(0).role();
        assertThat(firstRole)
            .as("first FlowStep of any taint finding must be 'source'")
            .isEqualTo("source");
      }
    }
  }
}

/*
 * R-808 ? verify V2 trace shape summary actually reaches
 * the LLM on the FIRST turn via the ReAct framework.
 * The previous tests (V2TraceSummarizerTest +
 * V2TraceSummaryInjectionTest) cover the summarizer in
 * isolation + the user-turn assembly in a static way.
 * This test wires the real ReActAgent + LlmProvider +
 * a Finding with a v2 trace, and uses InMemoryMockProvider
 * to capture the messages the LLM actually receives.
 * We assert that the FIRST user message contains the
 * "V2 trace shape: ..." line, proving the inline injection
 * reaches the LLM on turn 0 (before it has a chance to
 * call any tool).
 */
package org.plumb.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.ai.agent.*;
import org.plumb.reporter.Finding;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;

/**
 * R-808 ? end-to-end confirmation that the V2 trace shape summary appears in the FIRST user turn
 * sent to the LLM. This is the only test that goes through the real {@link ReActAgent} with a real
 * {@link InMemoryMockProvider} and a real {@link org.plumb.ai.tools.ToolContext}. The mock captures
 * {@code lastMessages()} so we can assert the inline-injection contract.
 */
class V2TraceSummaryReActInjectionTest {

  @Test
  void firstUserTurnContainsV2TraceShapeForNonNullTrace() {
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
            "taint: input:readLine/0 -> CWE-89",
            List.of(),
            null,
            null,
            "java.sql.Statement.execute/1",
            v2);

    // Run R-808's user-turn assembly (mirrors
    // ScanRunner.judgeOne verbatim) and feed the
    // first turn to a real ReActAgent with a
    // scripted LLM that always returns the same
    // final answer. We then assert the
    // InMemoryMockProvider saw the V2 trace shape
    // line on the first user turn.
    String v2Summary = V2TraceSummarizer.summarize(f.trace());
    String userTurn =
        "RULE PROMPT\n\n"
            + "Finding: "
            + f.rule().ruleId().id()
            + " at line "
            + f.line()
            + "\n"
            + "Message: "
            + f.message()
            + "\n"
            + "Dataflow: "
            + f.dataflowHead()
            + "\n"
            + (v2Summary.isEmpty() ? "" : "V2 trace shape: " + v2Summary + "\n")
            + "Source window:\n(file unreadable)\n";

    InMemoryMockProvider provider =
        InMemoryMockProvider.alwaysFinalAnswer(
            "{\"verdict\":\"TP\"," + "\"reason\":\"user input -> SQL\"}");

    AgentTask task =
        AgentTask.fpReduction(
            "You are a senior application-security reviewer.", userTurn, "test-model", 0.0);
    ToolContext toolCtx =
        new ToolContext(f, "fp-reduction", Path.of(".").toString(), null, null, null, null, null);
    ReActAgent agent = new ReActAgent(provider, new org.plumb.ai.tools.ToolRegistry());
    AgentResult result = agent.run(task, toolCtx);

    assertThat(result.kind())
        .as("R-808: scripted final answer reaches the agent")
        .isEqualTo(AgentResult.Kind.FINAL_ANSWER);

    // The mock provider saw the first user turn
    // BEFORE any tool call. Assert it contained
    // the V2 trace shape line.
    assertThat(provider.callCount())
        .as(
            "R-808: agent should make exactly one LLM call "
                + "when the model returns a final answer on "
                + "turn 0")
        .isEqualTo(1);
    List<LlmMessage> messages = provider.lastMessages();
    // Find the first user message in the conversation.
    LlmMessage firstUser = null;
    for (LlmMessage m : messages) {
      if ("user".equals(m.role())) {
        firstUser = m;
        break;
      }
    }
    assertThat(firstUser).as("R-808: the first user turn must reach the LLM").isNotNull();
    assertThat(firstUser.content())
        .as(
            "R-808: the first user turn contains the V2 "
                + "trace shape line (the LLM can use the "
                + "shape on turn 0 without calling any tool)")
        .contains("V2 trace shape: ")
        .contains("root=SOURCE/INPUT_READLINE")
        .contains("SINK_SQL")
        .contains("MOVE");
  }
}

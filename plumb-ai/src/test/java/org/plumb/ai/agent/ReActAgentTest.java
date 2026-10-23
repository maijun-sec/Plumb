package org.plumb.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.plumb.ai.tools.*;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-? — Tests for {@link ReActAgent} driven by an {@link InMemoryMockProvider}. The mock returns
 * scripted {@link LlmReply} values so we can exercise the loop without a network round-trip.
 *
 * <p>Each test covers one stop condition / one tool path. The tests are deliberately small and
 * self-contained: the mock is the only LLM the agent sees, the tool registry has one tool (or a
 * few), and the {@link ToolContext} is built with the bare minimum.
 */
class ReActAgentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── termination paths ──────────────────────────────

    @Test
    void singleTurnFinalAnswerStopsImmediately() {
        // The model returns FINAL: on the first turn. No
        // tools. The agent must stop after 1 turn.
        InMemoryMockProvider mock =
                InMemoryMockProvider.script(
                        List.of(
                                InMemoryMockProvider.ScriptedReply.finalAnswer(
                                        "{\"verdict\":\"FP\",\"reason\":\"sanitised by setString\"}")));

        ReActAgent agent = new ReActAgent(mock, new ToolRegistry());
        AgentResult result =
                agent.run(AgentTask.fpReduction("system", "user", "gpt-test", 0.0), ctx(null));

        assertThat(result.isFinalAnswer()).isTrue();
        assertThat(result.verdict()).contains("FP");
        assertThat(mock.callCount()).isEqualTo(1);
    }

    @Test
    void multipleToolCallsThenFinalAnswer() {
        // The model calls read_source, then has_sanitizer_on_path,
        // then returns FINAL:. The agent must dispatch both tools
        // and only then stop.
        InMemoryMockProvider mock =
                InMemoryMockProvider.script(
                        List.of(
                                InMemoryMockProvider.ScriptedReply.toolCall(
                                        "read_source", "{\"path\":\"/x.java\"}"),
                                InMemoryMockProvider.ScriptedReply.toolCall(
                                        "has_sanitizer_on_path", "{\"var\":\"x\",\"from\":1,\"to\":50}"),
                                InMemoryMockProvider.ScriptedReply.finalAnswer(
                                        "{\"verdict\":\"TP\",\"reason\":\"no sanitizer found\"}")));

        ReActAgent agent =
                new ReActAgent(
                        mock,
                        new ToolRegistry()
                                .register(new ReadSourceTool())
                                .register(new HasSanitizerOnPathTool()));
        AgentResult result =
                agent.run(AgentTask.fpReduction("system", "user", "gpt-test", 0.0), ctx(null));

        assertThat(result.isFinalAnswer()).isTrue();
        assertThat(result.verdict()).contains("TP");
        assertThat(result.toolCallLog()).hasSize(2);
        // The first call was read_source, the second was
        // has_sanitizer_on_path.
        assertThat(result.toolCallLog().get(0).name()).isEqualTo("read_source");
        assertThat(result.toolCallLog().get(1).name()).isEqualTo("has_sanitizer_on_path");
        assertThat(mock.callCount()).isEqualTo(3);
    }

    @Test
    void maxTurnsExceededWhenModelKeepsCallingTools() {
        // The model only ever calls tools, never FINAL:. After
        // maxTurns the agent must return MAX_TURNS_EXCEEDED.
        InMemoryMockProvider mock =
                InMemoryMockProvider.alwaysToolCall("read_source", "{\"path\":\"/x.java\"}");

        ReActAgent agent = new ReActAgent(mock, new ToolRegistry().register(new ReadSourceTool()));
        AgentResult result =
                agent.run(
                        AgentTask.fpReduction("system", "user", "gpt-test", 0.0).withMaxTurns(3), ctx(null));

        assertThat(result.kind()).isEqualTo(AgentResult.Kind.MAX_TURNS_EXCEEDED);
        assertThat(result.maxTurns()).isEqualTo(3);
        assertThat(mock.callCount()).isEqualTo(3);
    }

    @Test
    void providerErrorWhenMockReturnsNull() {
        // Every call returns null → the agent short-circuits
        // with PROVIDER_ERROR.
        InMemoryMockProvider mock = InMemoryMockProvider.alwaysNull();

        ReActAgent agent = new ReActAgent(mock, new ToolRegistry());
        AgentResult result =
                agent.run(AgentTask.fpReduction("system", "user", "gpt-test", 0.0), ctx(null));

        assertThat(result.kind()).isEqualTo(AgentResult.Kind.PROVIDER_ERROR);
        assertThat(result.error()).contains("null");
    }

    @Test
    void providerErrorWhenMockIsUnavailable() {
        // isAvailable() == false → the agent short-circuits
        // before the first call.
        InMemoryMockProvider mock =
                InMemoryMockProvider.alwaysFinalAnswer("{\"verdict\":\"FP\",\"reason\":\"x\"}")
                        .setAvailable(false);

        ReActAgent agent = new ReActAgent(mock, new ToolRegistry());
        AgentResult result =
                agent.run(AgentTask.fpReduction("system", "user", "gpt-test", 0.0), ctx(null));

        assertThat(result.kind()).isEqualTo(AgentResult.Kind.PROVIDER_ERROR);
        assertThat(result.error()).isEqualTo("llm-provider-not-available");
        assertThat(mock.callCount()).isEqualTo(0);
    }

    @Test
    void silentTextReplyTriggersNudgeThenStops() {
        // The model returns a non-FINAL: text reply with no
        // tool calls. The agent nudges once, then bails.
        InMemoryMockProvider mock =
                InMemoryMockProvider.script(
                        List.of(
                                InMemoryMockProvider.ScriptedReply.silentText("I'm not sure, let me look again..."),
                                InMemoryMockProvider.ScriptedReply.finalAnswer(
                                        "{\"verdict\":\"UNKNOWN\",\"reason\":\"could not decide\"}")));

        ReActAgent agent = new ReActAgent(mock, new ToolRegistry());
        AgentResult result =
                agent.run(AgentTask.fpReduction("system", "user", "gpt-test", 0.0), ctx(null));

        // 2 turns: silent + final
        assertThat(mock.callCount()).isEqualTo(2);
        assertThat(result.isFinalAnswer()).isTrue();
        assertThat(result.verdict()).contains("UNKNOWN");
    }

    // ─── parsing paths ─────────────────────────────────

    @Test
    void finalAnswerBodyStripsPrefix() {
        // The FINAL: prefix is removed from the verdict body
        // before the caller sees it. The body should be a
        // clean JSON object.
        InMemoryMockProvider mock =
                InMemoryMockProvider.script(
                        List.of(
                                InMemoryMockProvider.ScriptedReply.finalAnswer(
                                        "{\"verdict\":\"TP\",\"reason\":\"r\"}")));

        ReActAgent agent = new ReActAgent(mock, new ToolRegistry());
        AgentResult result =
                agent.run(AgentTask.fpReduction("system", "user", "gpt-test", 0.0), ctx(null));

        assertThat(result.verdict()).doesNotStartWith("FINAL:");
        assertThat(result.verdict()).startsWith("{");
    }

    @Test
    void toolErrorPropagatesToLlm() {
        // A tool throws → the registry returns
        // ToolResult.error(...). The agent sends the error
        // back to the LLM as the tool body, and the loop
        // continues. The LLM then finalises.
        InMemoryMockProvider mock =
                InMemoryMockProvider.script(
                        List.of(
                                InMemoryMockProvider.ScriptedReply.toolCall("broken_tool", "{}"),
                                InMemoryMockProvider.ScriptedReply.finalAnswer(
                                        "{\"verdict\":\"FP\",\"reason\":\"tool errored, "
                                                + "assumed sanitised based on context\"}")));

        ToolRegistry tools =
                new ToolRegistry()
                        .register(
                                new Tool() {
                                    @Override
                                    public String name() {
                                        return "broken_tool";
                                    }

                                    @Override
                                    public String description() {
                                        return "always throws";
                                    }

                                    @Override
                                    public ToolDefinition definition() {
                                        return new ToolDefinition(name(), description(), Map.of(), List.of());
                                    }

                                    @Override
                                    public ToolResult execute(ToolContext ctx, JsonNode args) {
                                        return ToolResult.error("simulated failure");
                                    }
                                });
        ReActAgent agent = new ReActAgent(mock, tools);
        AgentResult result =
                agent.run(AgentTask.fpReduction("system", "user", "gpt-test", 0.0), ctx(null));

        assertThat(result.isFinalAnswer()).isTrue();
        assertThat(result.verdict()).contains("FP");
        // Verify the tool result reached the LLM (it should
        // appear in the assistant turn's tool message on the
        // 2nd call).
        List<LlmMessage> secondCall = mock.allMessages().get(1);
        boolean gotToolResult =
                secondCall.stream()
                        .anyMatch(
                                m ->
                                        "tool".equals(m.role())
                                                && m.content() != null
                                                && m.content().contains("simulated failure"));
        assertThat(gotToolResult)
                .as("broken_tool's error should reach the LLM as the tool body")
                .isTrue();
    }

    @Test
    void unknownToolReturnsErrorAndKeepsRunning() {
        // The LLM asks for a tool that isn't registered. The
        // registry returns a friendly error, the agent feeds
        // it back, the LLM retries with a known tool, and
        // finalises.
        InMemoryMockProvider mock =
                InMemoryMockProvider.script(
                        List.of(
                                InMemoryMockProvider.ScriptedReply.toolCall("does_not_exist", "{}"),
                                InMemoryMockProvider.ScriptedReply.toolCall(
                                        "read_source", "{\"path\":\"/x.java\"}"),
                                InMemoryMockProvider.ScriptedReply.finalAnswer(
                                        "{\"verdict\":\"TP\",\"reason\":\"x\"}")));

        ReActAgent agent = new ReActAgent(mock, new ToolRegistry().register(new ReadSourceTool()));
        AgentResult result =
                agent.run(AgentTask.fpReduction("system", "user", "gpt-test", 0.0), ctx(null));

        assertThat(result.isFinalAnswer()).isTrue();
        assertThat(result.toolCallLog()).hasSize(2);
    }

    @Test
    void messagesConvergeToUserTurnThenAssistantThenToolResults() {
        // Spot-check the conversation shape: turn 1 the agent
        // sends [system, user]; turn 2 (after a tool call) it
        // sends [system, user, assistant(with tool_calls),
        // tool(result)]. This is what OpenAI's API expects.
        InMemoryMockProvider mock =
                InMemoryMockProvider.script(
                        List.of(
                                InMemoryMockProvider.ScriptedReply.toolCall(
                                        "read_source", "{\"path\":\"/x.java\"}"),
                                InMemoryMockProvider.ScriptedReply.finalAnswer(
                                        "{\"verdict\":\"TP\",\"reason\":\"x\"}")));

        ReActAgent agent = new ReActAgent(mock, new ToolRegistry().register(new ReadSourceTool()));
        agent.run(AgentTask.fpReduction("system", "user", "gpt-test", 0.0), ctx(null));

        List<List<LlmMessage>> all = mock.allMessages();
        assertThat(all).hasSize(2);
        // Turn 1: system + user.
        assertThat(all.get(0)).hasSize(2);
        assertThat(all.get(0).get(0).role()).isEqualTo("system");
        assertThat(all.get(0).get(1).role()).isEqualTo("user");
        // Turn 2: system + user + assistant (with tool_calls)
        // + tool (with the result).
        assertThat(all.get(1)).hasSize(4);
        assertThat(all.get(1).get(2).role()).isEqualTo("assistant");
        assertThat(all.get(1).get(2).toolCalls()).hasSize(1);
        assertThat(all.get(1).get(3).role()).isEqualTo("tool");
        assertThat(all.get(1).get(3).toolCallId()).isEqualTo(all.get(1).get(2).toolCalls().get(0).id());
    }

    // ─── helpers ────────────────────────────────────────

    @Test
    void r668_firstTurnUsesRequiredToolChoice() {
        // AgentTask.fpReduction sets
        // forceFirstTurnToolRequired=true. The agent must
        // pass tool_choice="required" on turn 0, and
        // tool_choice="auto" on subsequent turns. This
        // prevents the model from skipping tool use on
        // the first turn.
        InMemoryMockProvider mock =
                InMemoryMockProvider.script(
                        List.of(
                                InMemoryMockProvider.ScriptedReply.toolCall(
                                        "read_source", "{\"path\":\"/x.java\"}"),
                                InMemoryMockProvider.ScriptedReply.toolCall(
                                        "read_source", "{\"path\":\"/y.java\"}"),
                                InMemoryMockProvider.ScriptedReply.finalAnswer(
                                        "{\"verdict\":\"FP\",\"reason\":\"x\"}")));

        ReActAgent agent = new ReActAgent(mock, new ToolRegistry().register(new ReadSourceTool()));
        agent.run(AgentTask.fpReduction("system", "user", "gpt-test", 0.0), ctx(null));

        List<String> choices = mock.seenToolChoices();
        assertThat(choices).hasSize(3);
        assertThat(choices.get(0)).isEqualTo("required");
        assertThat(choices.get(1)).isEqualTo("auto");
        assertThat(choices.get(2)).isEqualTo("auto");
    }

    @Test
    void r668_disablingForceAllowsAutoFromStart() {
        // When the task sets forceFirstTurnToolRequired=false,
        // the first turn should use "auto" (not "required").
        InMemoryMockProvider mock =
                InMemoryMockProvider.script(
                        List.of(
                                InMemoryMockProvider.ScriptedReply.finalAnswer(
                                        "{\"verdict\":\"FP\",\"reason\":\"x\"}")));

        ReActAgent agent = new ReActAgent(mock, new ToolRegistry());
        agent.run(
                AgentTask.fpReduction("system", "user", "gpt-test", 0.0)
                        .withForceFirstTurnToolRequired(false),
                ctx(null));

        assertThat(mock.seenToolChoices().get(0)).isEqualTo("auto");
    }

    private static ToolContext ctx(Finding f) {
        return new ToolContext(f, "fp-reduction", ".", List.of(), List.of(), null, null, null);
    }

    @SuppressWarnings("unused")
    private static Finding dummyFinding() {
        RuleDef rule =
                new RuleDef(
                        new RuleId("bu-0301-cwe89", "SQL_INJECTION"),
                        "SQL injection",
                        RuleDef.Severity.HIGH,
                        RuleDef.Type.SECURITY,
                        List.of("CWE-89"),
                        "org.plumb.dfa.checkers.taint.SqlInjectionChecker",
                        true);
        return new Finding(
                java.nio.file.Path.of("dummy.java"), 10, 4, rule, "msg", RuleDef.Severity.HIGH, "head");
    }
}

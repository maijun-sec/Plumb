package org.plumb.ai.agent;

import org.plumb.ai.tools.ToolContext;
import org.plumb.ai.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The Re-Act (Reason + Act) loop. The agent sends a user task to the LLM, parses the reply,
 * dispatches any tool calls, feeds the results back, and loops until the model produces a {@code
 * "FINAL:"} answer or the iteration cap is hit.
 *
 * <h2>Conversation shape</h2>
 *
 * <pre>
 *   system:    <role-priming prompt from AgentTask>
 *   user:      <the actual question>
 *   assistant: <reply 1 ? may carry tool_calls>
 *   tool:      <result of tool 1, paired by tool_call_id>
 *   tool:      <result of tool 2, ...>
 *   assistant: <reply 2 ? may carry tool_calls>
 *   ...
 *   assistant: FINAL: {verdict, reason}            <-- stop
 * </pre>
 *
 * <h2>Stop conditions</h2>
 *
 * <ol>
 *   <li>The model produces an assistant turn with no tool calls whose content starts with {@code
 *       "FINAL:"}.
 *   <li>The iteration cap is hit (every turn produced tool calls that never converged). The agent
 *       returns {@link AgentResult#maxTurnsExceeded(int, LlmReply, java.util.List)} with the
 *       accumulated {@code toolCallLog} so callers can diagnose the runaway.
 *   <li>The {@link LlmProvider} returns {@code null} on a call (transport / parse failure).
 *   <li>The previous turn's tool calls all returned errors AND the model now gives up (no tool
 *       calls, no FINAL:). We bail with {@link AgentResult#toolError(String, LlmReply)} so the
 *       caller can map it to UNKNOWN ? this distinguishes "LLM didn't conclude" from "the tool
 *       layer is broken".
 * </ol>
 *
 * <h2>Provider abstraction</h2>
 *
 * <p>The agent takes a {@link LlmProvider} interface, not the {@code AIProvider} class directly.
 * This lets tests inject an {@link InMemoryMockProvider} that returns scripted replies without
 * going through the network. The production wiring uses {@code AIProviderAdapter} which wraps the
 * existing {@code AIProvider} HTTP client.
 */
public class ReActAgent {

    private static final Logger LOG = LoggerFactory.getLogger(ReActAgent.class);

    private final LlmProvider provider;
    private final ToolRegistry tools;

    public ReActAgent(LlmProvider provider, ToolRegistry tools) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    /**
     * Run the Re-Act loop for one task. Returns a structured {@link AgentResult} the caller maps to
     * its domain (FP reducer → verdict, bug scanner → findings list).
     *
     * <h2>Tool choice per turn</h2>
     *
     * <ul>
     *   <li>Turn 0 (first call): {@code "required"} when the task sets {@link
     *       AgentTask#forceFirstTurnToolRequired()}, else {@code "auto"}. "required" prevents the
     *       model from skipping navigation on the first turn (finding: model was treating most cases
     *       as "trivial" and answering without any tool call, then hallucinating about the source
     *       code).
     *   <li>Turn 1+: {@code "auto"} ? let the model decide. Once it has the source context, it can
     *       finalise directly or chain more tool calls.
     * </ul>
     */
    public AgentResult run(AgentTask task, ToolContext ctx) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(ctx, "ctx");
        if (!provider.isAvailable()) {
            return AgentResult.providerError("llm-provider-not-available");
        }
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(task.systemPrompt()));
        messages.add(LlmMessage.user(task.userPrompt()));

        List<ToolCall> toolCallLog = new ArrayList<>();
        LlmReply lastReply = null;
        int max = task.maxTurns() > 0 ? task.maxTurns() : 5;
        String firstTurnChoice =
                task.forceFirstTurnToolRequired()
                        ? LlmProvider.TOOL_CHOICE_REQUIRED
                        : LlmProvider.TOOL_CHOICE_AUTO;

        // Track whether the previous turn's tool calls all
        // errored AND the model has now given up (no tool
        // calls, no FINAL:). Per AgentResult#toolError
        // contract, this is the bail-out path for
        // "every tool call returns error AND model gives
        // up" — bail instead of looping to MAX_TURNS.
        boolean previousTurnAllErrors = false;
        String lastToolErrorBody = null;

        for (int turn = 0; turn < max; turn++) {
            String toolChoice = (turn == 0) ? firstTurnChoice : LlmProvider.TOOL_CHOICE_AUTO;
            LlmReply reply =
                    provider.chatWithTools(
                            messages, tools.definitions(), toolChoice, task.model(), task.temperature());
            if (reply == null) {
                LOG.warn("ReAct turn {}: provider returned null", turn);
                return AgentResult.providerError("llm-provider-returned-null on turn " + turn);
            }
            lastReply = reply;
            // Append the assistant turn to the conversation
            // (the model sees its own previous turn on the
            // next iteration).
            messages.add(LlmMessage.assistant(reply.content(), reply.toolCalls()));

            // Termination check 1: model gave a final answer.
            if (reply.isFinalAnswer()) {
                LOG.debug("ReAct done after {} turns, FINAL: {}", turn + 1, reply.finalBody());
                return AgentResult.finalAnswer(reply, reply.finalBody(), task.taskKind(), toolCallLog);
            }

            // Termination check 2: model produced no tool
            // calls and didn't say FINAL:. Two sub-cases:
            //   (a) previous turn's tools all errored → the
            //       model has given up; bail with TOOL_ERROR
            //       so the caller can map it to UNKNOWN
            //       instead of treating the run as
            //       "max-turns exceeded" (which would mask
            //       the underlying config / IR problem).
            //   (b) normal case → nudge once. If the nudge
            //       fails too, the loop continues to MAX_TURNS.
            if (reply.noToolCalls()) {
                if (previousTurnAllErrors) {
                    LOG.warn(
                            "ReAct turn {}: model gave up after all-error tool turn; "
                                    + "bailing with TOOL_ERROR. Last tool error: {}",
                            turn,
                            lastToolErrorBody);
                    return AgentResult.toolError(
                            "model gave up after all tool calls errored; first error: "
                                    + lastToolErrorBody,
                            reply);
                }
                // Last turn: skip the nudge and let the
                // loop hit MAX_TURNS instead of wasting a
                // turn on a reminder the model can't act on
                // (no further iteration will happen anyway).
                if (turn == max - 1) {
                    LOG.debug(
                            "ReAct turn {}: silent reply on last turn, no nudge (would "
                                    + "be wasted)",
                            turn);
                    continue;
                }
                messages.add(
                        LlmMessage.user("Reply with FINAL: {verdict, reason} (or [] for no findings)."));
                continue;
            }

            // Dispatch tool calls. Each tool sees the same
            // ToolContext (read-only). Errors are returned
            // to the LLM as the tool body so it can adapt
            // on the next turn.
            int errorCount = 0;
            String firstErrorBody = null;
            for (ToolCall call : reply.toolCalls()) {
                toolCallLog.add(call);
                ToolResult result = tools.execute(call.name(), ctx, call.args());
                if (!result.ok()) {
                    errorCount++;
                    if (firstErrorBody == null) firstErrorBody = result.body();
                }
                messages.add(LlmMessage.tool(call.id(), call.name(), result.render()));
            }
            if (errorCount > 0) {
                LOG.debug(
                        "ReAct turn {}: {} of {} tools returned error",
                        turn,
                        errorCount,
                        reply.toolCalls().size());
            }
            // Update the all-errors tracker: only true when
            // the model emitted at least one tool call AND
            // every single one returned an error. A
            // partial-success turn resets the tracker so
            // the model gets more chances.
            previousTurnAllErrors =
                    reply.toolCalls().size() > 0 && errorCount == reply.toolCalls().size();
            lastToolErrorBody = previousTurnAllErrors ? firstErrorBody : null;
        }
        // Termination check 3: max turns reached.
        LOG.warn("ReAct hit maxTurns={} without FINAL: from model", max);
        return AgentResult.maxTurnsExceeded(max, lastReply, toolCallLog);
    }
}

package org.plumb.ai.agent;

import java.util.List;

/**
 * The result of one Re-Act agent run. One of four shapes:
 *
 * <ul>
 *   <li>{@link #finalAnswer(LlmReply, String, String)} ? the model produced a {@code "FINAL:"}
 *       reply on the last turn. {@code verdict} is the body of the FINAL (the raw JSON string the
 *       model emitted). {@code taskKind} is the agent's role (e.g. {@code "fp-reduction"} or {@code
 *       "bug-scan"}). The {@link LlmReply} is the last assistant turn, useful for logging /
 *       metrics.
 *   <li>{@link #maxTurnsExceeded(int, LlmReply, List)} ? the model hit the iteration cap without
 *       producing a FINAL. {@code maxTurns} is the configured cap. The {@link LlmReply} is the last
 *       assistant turn, useful for diagnosing the runaway (often the model is stuck in a tool-call
 *       loop and the LLM SDK doesn't add a guard). The {@code toolCallLog} captures every tool call
 *       the agent dispatched before the bail, so callers can see "the model called read_source 47
 *       times in a row and never finalised" without losing the diagnostic context.
 *   <li>{@link #providerError(String)} ? the {@link LlmProvider} returned {@code null} on the first
 *       call (network failure, auth error, etc.). The agent short-circuits the run with the given
 *       error message; the caller maps it to UNKNOWN.
 *   <li>{@link #toolError(String, LlmReply)} ? the agent dispatched one or more tool calls and
 *       every single one returned an error, AND the model then gave up on the next turn (no tool
 *       calls, no FINAL:). Typical cause: all tools are mis-registered for this run (e.g. the
 *       FP-reducer agent was set up without the IR-dependent tools but the finding lives behind a
 *       {@code get_dataflow_trace_with_facts} requirement), or every tool was passed malformed
 *       arguments and the LLM couldn't recover. The agent bails with the given error message; the
 *       caller maps it to UNKNOWN.
 * </ul>
 *
 * <p>The {@code toolCallLog} field is a debug aid on every branch that produced tool calls: the
 * LLM's tool-call history in the order it made them. Useful for explaining to the user "the agent
 * called read_source then has_sanitizer_on_path and concluded FP" in a debug log, or "the agent
 * called read_source 47 times in a row and never finalised" on the MAX_TURNS_EXCEEDED branch.
 * {@link #providerError(String)} / {@link #toolError(String, LlmReply)} short-circuit before any
 * tool dispatch and therefore carry an empty log.
 */
public record AgentResult(
    Kind kind,
    LlmReply lastReply,
    String verdict, // raw body of the FINAL: line, or null
    String taskKind, // "fp-reduction" | "bug-scan"
    int maxTurns, // populated when kind == MAX_TURNS_EXCEEDED
    String error, // populated when kind == PROVIDER_ERROR or TOOL_ERROR
    List<ToolCall> toolCallLog) {

  public enum Kind {
    FINAL_ANSWER,
    MAX_TURNS_EXCEEDED,
    PROVIDER_ERROR,
    TOOL_ERROR
  }

  public static AgentResult finalAnswer(
      LlmReply lastReply, String verdict, String taskKind, List<ToolCall> toolCallLog) {
    return new AgentResult(
        Kind.FINAL_ANSWER, lastReply, verdict, taskKind, 0, null, List.copyOf(toolCallLog));
  }

  public static AgentResult maxTurnsExceeded(
      int maxTurns, LlmReply lastReply, List<ToolCall> toolCallLog) {
    return new AgentResult(
        Kind.MAX_TURNS_EXCEEDED, lastReply, null, null, maxTurns, null, List.copyOf(toolCallLog));
  }

  public static AgentResult providerError(String message) {
    return new AgentResult(Kind.PROVIDER_ERROR, null, null, null, 0, message, List.of());
  }

  public static AgentResult toolError(String message, LlmReply lastReply) {
    return new AgentResult(Kind.TOOL_ERROR, lastReply, null, null, 0, message, List.of());
  }

  public boolean isFinalAnswer() {
    return kind == Kind.FINAL_ANSWER;
  }
}

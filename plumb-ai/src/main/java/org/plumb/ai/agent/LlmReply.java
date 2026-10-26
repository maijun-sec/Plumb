package org.plumb.ai.agent;

import java.util.List;

/**
 * One model reply (one turn of the Re-Act loop).
 *
 * <p>The reply carries:
 *
 * <ul>
 *   <li>{@link #content} -- the model's free-form text. Empty when the model decided to call tools
 *       without any prose. The "final answer" detection scans this for a {@code "FINAL:"} prefix.
 *   <li>{@link #toolCalls} -- the model's tool requests. Empty when the model decided to answer
 *       directly.
 *   <li>{@link #finishReason} -- OpenAI's {@code choices[0].finish_reason} field. {@code "stop"}
 *       means the model finished its turn, {@code "tool_calls"} means the model wants to call
 *       tools, {@code "length"} means it ran out of tokens.
 * </ul>
 *
 * <p>{@link #isFinalAnswer()} returns {@code true} when the model wrote a {@code "FINAL:"} prefix
 * with no tool calls pending. The Re-Act loop treats this as the stop condition.
 */
public record LlmReply(String content, List<ToolCall> toolCalls, String finishReason) {

  public LlmReply {
    if (toolCalls == null) toolCalls = List.of();
  }

  /** True when the model produced no tool calls. */
  public boolean noToolCalls() {
    return toolCalls.isEmpty();
  }

  /**
   * True when the reply is the model's final answer. The model signals "I'm done" by writing {@code
   * "FINAL:"} at the start of its reply (the system prompt instructs this). Tool calls + FINAL: on
   * the same turn is allowed but the Re-Act loop ignores the FINAL: in that case (it'll be picked
   * up on the next iteration after the tool results come back).
   */
  public boolean isFinalAnswer() {
    if (!noToolCalls()) return false;
    return content != null && content.trim().startsWith("FINAL:");
  }

  /** Strip the {@code "FINAL:"} prefix and return the remainder. Used to parse the final JSON. */
  public String finalBody() {
    if (content == null) return "";
    String trimmed = content.trim();
    if (!trimmed.startsWith("FINAL:")) return trimmed;
    return trimmed.substring("FINAL:".length()).trim();
  }
}

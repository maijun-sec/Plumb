package org.plumb.ai.agent;

import java.util.List;

/**
 * Abstraction over the LLM HTTP client. The Re-Act agent depends on this interface (not on {@code
 * AIProvider} directly) so tests can inject a {@link InMemoryMockProvider} that returns scripted
 * replies without going through the network.
 *
 * <p>Two methods cover the two LLM call shapes the agent needs:
 *
 * <ul>
 *   <li>{@link #chatWithTools(List, List, String, double)} ? the main loop's call. Carries the full
 *       conversation history, the tool catalog, the model name, and the temperature. Returns the
 *       model's reply (one turn).
 *   <li>{@link #isAvailable()} ? non-blocking liveness check. Used by the agent to fail fast when
 *       no LLM is configured (returns {@code false}) and degrade to UNKNOWN instead of trying a
 *       network call that will 401.
 * </ul>
 */
public interface LlmProvider {

  /** OpenAI tool_choice default ? let the model decide. */
  String TOOL_CHOICE_AUTO = "auto";

  /** OpenAI tool_choice ? model MUST call at least one tool. */
  String TOOL_CHOICE_REQUIRED = "required";

  /** OpenAI tool_choice ? explicitly disable tool use. */
  String TOOL_CHOICE_NONE = "none";

  /**
   * One chat-completion call (possibly with tool-calling). The {@code messages} list is the full
   * conversation history including the system message. The {@code tools} list is the tool catalog
   * for this agent (typically 4-10 entries; OpenAI accepts up to 128). The {@code toolChoice} is
   * the OpenAI {@code tool_choice} field: {@code "auto"} (default ? model decides), {@code
   * "required"} (model MUST call at least one tool this turn; used on the agent's first turn to
   * force navigation), or {@code "none"} (no tools this turn). The model name + temperature come
   * from the config. Returns {@code null} on transport / parse failure ? the agent treats {@code
   * null} as a fatal error for the run.
   */
  LlmReply chatWithTools(
      List<LlmMessage> messages,
      List<ToolDefinition> tools,
      String toolChoice,
      String model,
      double temperature);

  /**
   * Cheap liveness check. Returns {@code false} when the provider has no API key / endpoint
   * configured, or when a recent probe has shown the endpoint is unreachable. The agent calls this
   * before starting the loop so an unconfigured agent doesn't waste a bunch of tool calls on a
   * doomed network round-trip.
   */
  default boolean isAvailable() {
    return true;
  }
}

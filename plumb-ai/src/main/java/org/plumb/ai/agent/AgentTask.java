package org.plumb.ai.agent;

/**
 * The initial user turn the agent sends to the LLM. Carries:
 *
 * <ul>
 *   <li>{@link #systemPrompt()} ? the role-priming message (one per agent: FP-reducer /
 *       bug-scanner).
 *   <li>{@link #userPrompt()} ? the per-task question (e.g. "Is this finding TP or FP? Finding: ...
 *       Source: ... Dataflow: ..."). The agent wraps this in the first user turn.
 *   <li>{@link #taskKind()} ? a short label the agent echoes into {@link AgentResult#taskKind()}
 *       for the caller's downstream routing (e.g. "fp-reduction" vs "bug-scan").
 *   <li>{@link #maxTurns()} ? the iteration cap. Defaults to 5 in {@link ReActAgent}; callers can
 *       override.
 *   <li>{@link #model()} / {@link #temperature()} ? LLM call params. The agent passes them to
 *       {@link LlmProvider#chatWithTools}.
 *   <li>{@link #forceFirstTurnToolRequired()} ? tool-choice behaviour. When {@code true}, the first
 *       chat call uses {@code tool_choice: "required"} (the model MUST call at least one tool on
 *       the first turn). When {@code false}, the first call uses the standard {@code "auto"} (model
 *       decides). The default for {@link #fpReduction} is {@code true} because the whole point of
 *       the FP reducer is to inspect the source before judging ? answering without a tool call is
 *       by definition guessing.
 * </ul>
 */
public record AgentTask(
    String systemPrompt,
    String userPrompt,
    String taskKind,
    int maxTurns,
    String model,
    double temperature,
    boolean forceFirstTurnToolRequired) {

  public static AgentTask fpReduction(
      String systemPrompt, String userPrompt, String model, double temperature) {
    return new AgentTask(
        systemPrompt,
        userPrompt,
        "fp-reduction",
        5,
        model,
        temperature,
        /* forceFirstTurnToolRequired */ true);
  }

  public static AgentTask bugScan(
      String systemPrompt, String userPrompt, String model, double temperature) {
    return new AgentTask(
        systemPrompt,
        userPrompt,
        "bug-scan",
        8,
        model,
        temperature,
        /* forceFirstTurnToolRequired */ true);
  }

  public AgentTask withMaxTurns(int max) {
    return new AgentTask(
        systemPrompt, userPrompt, taskKind, max, model, temperature, forceFirstTurnToolRequired);
  }

  public AgentTask withForceFirstTurnToolRequired(boolean v) {
    return new AgentTask(systemPrompt, userPrompt, taskKind, maxTurns, model, temperature, v);
  }
}

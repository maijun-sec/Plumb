/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.ai.agent;

/**
 * The initial user turn the agent sends to the LLM. Carries:
 *
 * <ul>
 *   <li>{@link #systemPrompt()} -- the role-priming message (one per agent: FP-reducer /
 *       bug-scanner).
 *   <li>{@link #userPrompt()} -- the per-task question (e.g. "Is this finding TP or FP? Finding:
 *       ... Source: ... Dataflow: ..."). The agent wraps this in the first user turn.
 *   <li>{@link #taskKind()} -- a short label the agent echoes into {@link AgentResult#taskKind()}
 *       for the caller's downstream routing (e.g. "fp-reduction" vs "bug-scan").
 *   <li>{@link #maxTurns()} -- the iteration cap. Defaults to 5 in {@link ReActAgent}; callers can
 *       override.
 *   <li>{@link #model()} / {@link #temperature()} -- LLM call params. The agent passes them to
 *       {@link LlmProvider#chatWithTools}.
 *   <li>{@link #forceFirstTurnToolRequired()} -- tool-choice behaviour. When {@code true}, the
 *       first chat call uses {@code tool_choice: "required"} (the model MUST call at least one tool
 *       on the first turn). When {@code false}, the first call uses the standard {@code "auto"}
 *       (model decides). The default for {@link #fpReduction} is {@code true} because the whole
 *       point of the FP reducer is to inspect the source before judging -- answering without a tool
 *       call is by definition guessing.
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

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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * — A scripted {@link LlmProvider} for unit tests. The mock is fed a list of {@link ScriptedReply}
 * entries ahead of time and returns them in order, regardless of the conversation history. Useful
 * for testing the Re-Act loop without going through the network.
 *
 * <h2>Behaviours</h2>
 *
 * <ul>
 *   <li>{@link #script(List)} ? pre-script the conversation: the Nth call to {@link #chatWithTools}
 *       returns the Nth entry. Throws {@link AssertionError} if the loop asks for more turns than
 *       the script has entries (so "max turns exceeded" is testable too ? just script fewer entries
 *       than {@code maxTurns}).
 *   <li>{@link #alwaysFinalAnswer(String)} ? every call returns the same final-answer reply. Useful
 *       for the "no tools needed, judge directly" path.
 *   <li>{@link #alwaysNull()} ? every call returns {@code null} (simulates network failure → tests
 *       the {@code PROVIDER_ERROR} branch).
 *   <li>{@link #alwaysToolCall(String, ...)} ? every call returns a single tool call with the given
 *       name + arguments. Useful for the "agent keeps calling tools, never finalises" runaway path.
 * </ul>
 *
 * <h2>Helpers for assertions</h2>
 *
 * <p>After the run, {@link #callCount()} returns the number of {@code chatWithTools} invocations
 * (each call = one turn of the Re-Act loop). {@link #lastMessages()} returns the most recent
 * conversation history the agent sent (so tests can assert the tool results reached the LLM).
 */
public final class InMemoryMockProvider implements LlmProvider {

  private final List<ScriptedReply> script;
  private final AtomicInteger cursor = new AtomicInteger(0);
  private final List<List<LlmMessage>> seenMessages = new ArrayList<>();
  private final List<String> seenToolChoices = new ArrayList<>();
  private volatile boolean available = true;

  private InMemoryMockProvider(List<ScriptedReply> script) {
    this.script = script;
  }

  /**
   * Script a sequence of replies. The Nth call returns the Nth entry. Each entry may be a final
   * answer, a tool call, or a mix (use the {@link ScriptedReply} factory methods).
   */
  public static InMemoryMockProvider script(List<ScriptedReply> script) {
    Objects.requireNonNull(script, "script");
    return new InMemoryMockProvider(new ArrayList<>(script));
  }

  /**
   * Every call returns the same final-answer reply. Scripts enough entries to outlive a 32-turn
   * loop (caller can override {@code turns} if they need more).
   */
  public static InMemoryMockProvider alwaysFinalAnswer(String body) {
    return alwaysFinalAnswer(body, 32);
  }

  public static InMemoryMockProvider alwaysFinalAnswer(String body, int turns) {
    java.util.List<ScriptedReply> entries = new java.util.ArrayList<>(turns);
    for (int i = 0; i < turns; i++) {
      entries.add(ScriptedReply.finalAnswer(body));
    }
    return script(entries);
  }

  /**
   * Every call returns {@code null} (simulate network failure). The Re-Act agent should map this to
   * {@code PROVIDER_ERROR}.
   */
  public static InMemoryMockProvider alwaysNull() {
    return alwaysNull(32);
  }

  public static InMemoryMockProvider alwaysNull(int turns) {
    java.util.List<ScriptedReply> entries = new java.util.ArrayList<>(turns);
    for (int i = 0; i < turns; i++) {
      entries.add(ScriptedReply.nullReply());
    }
    return script(entries);
  }

  /**
   * Every call returns a single tool call with the given name + arguments. The agent never sees a
   * FINAL: reply, so it should hit {@code MAX_TURNS_EXCEEDED}.
   */
  public static InMemoryMockProvider alwaysToolCall(String toolName, String argsJson) {
    return alwaysToolCall(toolName, argsJson, 32);
  }

  public static InMemoryMockProvider alwaysToolCall(String toolName, String argsJson, int turns) {
    java.util.List<ScriptedReply> entries = new java.util.ArrayList<>(turns);
    for (int i = 0; i < turns; i++) {
      entries.add(ScriptedReply.toolCall(toolName, argsJson));
    }
    return script(entries);
  }

  @Override
  public LlmReply chatWithTools(
      List<LlmMessage> messages,
      List<ToolDefinition> tools,
      String toolChoice,
      String model,
      double temperature) {
    seenMessages.add(new ArrayList<>(messages));
    seenToolChoices.add(toolChoice == null ? "(null)" : toolChoice);
    int idx = cursor.getAndIncrement();
    if (idx >= script.size()) {
      // The loop asked for more turns than we
      // scripted. Default behaviour: throw, so a
      // test that didn't anticipate the extra turn
      // fails loudly. (Tests that want the
      // max-turns path supply just enough entries
      // for {@code maxTurns - 1} turns and rely on
      // this throw to surface the runaway.)
      throw new AssertionError(
          "InMemoryMockProvider ran out of scripted replies "
              + "after "
              + script.size()
              + " turn(s); got turn "
              + (idx + 1));
    }
    return script.get(idx).resolve();
  }

  @Override
  public boolean isAvailable() {
    return available;
  }

  /**
   * Force {@link #isAvailable()} to return {@code false} for the test of the "agent short-circuits
   * on missing LLM" path.
   */
  public InMemoryMockProvider setAvailable(boolean v) {
    this.available = v;
    return this;
  }

  /** Number of {@code chatWithTools} calls the agent made. */
  public int callCount() {
    return cursor.get();
  }

  /** Most recent conversation history the agent sent. */
  public List<LlmMessage> lastMessages() {
    if (seenMessages.isEmpty()) return List.of();
    return seenMessages.get(seenMessages.size() - 1);
  }

  /**
   * Every {@code tool_choice} the agent asked for, in call order. tests use this to assert the
   * first-turn {@code "required"} behaviour.
   */
  public List<String> seenToolChoices() {
    return List.copyOf(seenToolChoices);
  }

  /** Every conversation history snapshot, in call order. */
  public List<List<LlmMessage>> allMessages() {
    return List.copyOf(seenMessages);
  }

  /**
   * One scripted reply. The LLM may produce: text only, a tool call only, or text + tool call (the
   * LLM occasionally does this on the final turn). The mock builder below covers the three shapes.
   */
  public static final class ScriptedReply {
    private final String content;
    private final List<ToolCall> toolCalls;
    private final boolean asNull;
    private final String finishReason;

    private ScriptedReply(
        String content, List<ToolCall> toolCalls, boolean asNull, String finishReason) {
      this.content = content;
      this.toolCalls = toolCalls == null ? List.of() : toolCalls;
      this.asNull = asNull;
      this.finishReason = finishReason;
    }

    LlmReply resolve() {
      if (asNull) return null;
      return new LlmReply(content == null ? "" : content, toolCalls, finishReason);
    }

    /**
     * The model said {@code "FINAL: <body>"} (no tool calls). The Re-Act loop treats this as the
     * stop condition.
     */
    public static ScriptedReply finalAnswer(String body) {
      String text = "FINAL: " + (body == null ? "" : body);
      return new ScriptedReply(text, List.of(), false, "stop");
    }

    /**
     * The model said nothing useful and just called one tool. The agent will execute the tool and
     * loop.
     */
    public static ScriptedReply toolCall(String toolName, String argsJson) {
      com.fasterxml.jackson.databind.ObjectMapper m =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode args;
      try {
        args = m.readTree(argsJson == null ? "{}" : argsJson);
      } catch (Exception e) {
        args = m.createObjectNode();
      }
      ToolCall tc = new ToolCall("call_mock_" + System.nanoTime(), toolName, args);
      return new ScriptedReply("", List.of(tc), false, "tool_calls");
    }

    /**
     * The model produced a text reply without FINAL: or tool calls. The Re-Act loop nudges it once,
     * then bails. Useful for testing the "model went silent" path.
     */
    public static ScriptedReply silentText(String text) {
      return new ScriptedReply(text, List.of(), false, "stop");
    }

    /** Network failure: returns null. */
    public static ScriptedReply nullReply() {
      return new ScriptedReply(null, List.of(), true, null);
    }
  }
}

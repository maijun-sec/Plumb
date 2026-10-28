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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Low-level HTTP client for OpenAI-compatible {@code /v1/chat/completions} and Anthropic {@code
 * /v1/messages}. Implements {@link LlmProvider} so the Re-Act agent can call it. Replaces the
 * deleted {@code org.plumb.ai.AIProvider} but takes the {@link LlmConnection} record instead of a
 * config object (the config abstraction went away with the per-rule prompt registry).
 *
 * <h2>Why "Compatible" instead of "OpenAiLlmProvider"</h2>
 *
 * <p>The class name reflects the supported endpoint shape, not the underlying vendor. Any HTTP
 * endpoint that speaks the OpenAI {@code /v1/chat/completions} JSON contract is reachable: OpenAI
 * itself, Azure OpenAI, Ollama, vLLM, LocalAI, LiteLLM, etc. Anthropic users put an OpenAI-
 * compatible proxy (LiteLLM is the common choice) in front of {@code /v1/messages} so they get the
 * same tool-calling schema. Going direct to Anthropic's native {@code /v1/messages} is only
 * supported on the single-prompt path; the tool-calling path explicitly throws because the request
 * shape ({@code tools: [{name, description, input_schema}]} without the OpenAI {@code
 * type:"function"} wrapper) and the response shape ({@code content: [{type: "tool_use", ...}]}
 * blocks instead of {@code message.tool_calls[]}) are different enough that a vendor- specific
 * parser would double the wire-code size for marginal benefit.
 *
 * <h2>Two entry points</h2>
 *
 * <ul>
 *   <li>{@link #chat(String, String)} -- single-prompt call. Used by the "single-prompt" fallback
 *       in {@link ReActAgent} when the model doesn't need tools. Supports both OpenAI-shape and
 *       Anthropic native endpoints (it dispatches on {@link LlmConnection.Provider}).
 *   <li>{@link #chatWithTools(List, List, String, double)} -- Re-Act call. Carries the full
 *       conversation history and the tool catalog. Returns one {@link LlmReply} (text + optional
 *       tool calls). OpenAI-shape only -- throws {@link UnsupportedOperationException} for native
 *       Anthropic. The Re-Act agent handles the loop, this method only speaks the wire protocol.
 * </ul>
 *
 * <h2>Failure mode</h2>
 *
 * <p>Both entry points return {@code null} on transport / parse / auth failure. The Re-Act agent
 * maps {@code null} to {@code AgentResult.providerError(...)}; the single- prompt path maps it to
 * {@code verdict=UNKNOWN}. The caller decides whether UNKNOWN findings are kept or dropped.
 */
public final class OpenAiCompatibleLlmProvider implements LlmProvider {

  private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleLlmProvider.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final LlmConnection conn;
  private final HttpClient http;

  public OpenAiCompatibleLlmProvider(LlmConnection conn) {
    this.conn = Objects.requireNonNull(conn, "conn");
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Math.min(conn.timeoutSeconds(), 10)))
            .build();
  }

  // ─── LlmProvider implementation ─────────────────────

  /**
   * Cheap liveness check. Returns {@code false} when the connection record is empty (e.g. the user
   * didn't set {@code ai.baseUrl} / {@code ai.apiKey}). The Re-Act agent calls this before starting
   * the loop so a misconfigured agent doesn't waste a network round-trip.
   */
  @Override
  public boolean isAvailable() {
    return conn.isUsable();
  }

  // ─── single-prompt path ──────────────────────────────

  /**
   * One chat-completion call. Returns the assistant's text reply (already stripped of any markdown
   * fence) or {@code null} on transport / parse failure.
   */
  public String chat(String system, String user) {
    Objects.requireNonNull(system, "system");
    Objects.requireNonNull(user, "user");
    try {
      String requestBody = buildSinglePromptRequest(system, user);
      HttpResponse<String> resp = send(buildHttpRequest(requestBody));
      if (resp == null) return null;
      if (resp.statusCode() / 100 != 2) {
        LOG.warn(
            "AI HTTP {} ({}): {}", resp.statusCode(), conn.model(), truncate(resp.body(), 200));
        return null;
      }
      return extractText(resp.body());
    } catch (Exception e) {
      LOG.warn("AI request failed: {}", e.toString());
      return null;
    }
  }

  // ─── Re-Act path ─────────────────────────────────────

  /**
   * Re-Act entry point. One chat-completion call carrying the full conversation history + the tool
   * catalog. Returns the model's reply (one turn): text + optional tool calls. Returns {@code null}
   * on transport / parse failure; the Re-Act agent treats that as a fatal run error.
   *
   * <p>OpenAI's tool-calling schema is just JSON: the {@code tools} field is an array of {@code
   * {"type":"function","function":{...}}}, the response has {@code choices[0].message.tool_calls}
   * with the model-selected calls, each {@code {id, type, function: {name, arguments}}}.
   */
  @Override
  public LlmReply chatWithTools(
      List<LlmMessage> messages,
      List<ToolDefinition> tools,
      String toolChoice,
      String model,
      double temperature) {
    Objects.requireNonNull(messages, "messages");
    Objects.requireNonNull(tools, "tools");
    try {
      String requestBody =
          buildToolRequest(
              messages,
              tools,
              toolChoice == null ? LlmProvider.TOOL_CHOICE_AUTO : toolChoice,
              model == null ? conn.model() : model,
              temperature);
      HttpResponse<String> resp = send(buildHttpRequest(requestBody));
      if (resp == null) return null;
      if (resp.statusCode() / 100 != 2) {
        LOG.warn(
            "AI HTTP {} ({}): {}", resp.statusCode(), conn.model(), truncate(resp.body(), 200));
        return null;
      }
      return parseToolReply(resp.body());
    } catch (Exception e) {
      LOG.warn("AI tool request failed: {}", e.toString());
      return null;
    }
  }

  // ─── shared HTTP plumbing ────────────────────────────

  private HttpRequest.Builder buildHttpRequest(String requestBody) {
    HttpRequest.Builder rb =
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    conn.baseUrl()
                        + (conn.provider() == LlmConnection.Provider.ANTHROPIC
                            ? "/v1/messages"
                            : "/v1/chat/completions")))
            .timeout(Duration.ofSeconds(conn.timeoutSeconds()))
            .header("Content-Type", "application/json");
    if (!conn.apiKey().isEmpty()) {
      rb.header("Authorization", "Bearer " + conn.apiKey());
    }
    if (!conn.extraHeader().isEmpty()) {
      int colon = conn.extraHeader().indexOf(':');
      if (colon > 0) {
        String name = conn.extraHeader().substring(0, colon).trim();
        String value = conn.extraHeader().substring(colon + 1).trim();
        rb.header(name, value);
      }
    }
    rb.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
    return rb;
  }

  private HttpResponse<String> send(HttpRequest.Builder rb) {
    try {
      return http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      LOG.warn("AI send failed: {}", e.toString());
      return null;
    }
  }

  // ─── request bodies (single-prompt vs tool-calling) ──

  private String buildSinglePromptRequest(String system, String user) throws Exception {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("model", conn.model());
    if (conn.provider() == LlmConnection.Provider.ANTHROPIC) {
      // Anthropic requires max_tokens on every
      // request. The per-model lookup gives 200K for
      // Claude Sonnet/Opus — Claude will happily
      // generate that much when asked.
      root.put("max_tokens", conn.effectiveMaxTokens());
      ArrayNode messages = root.putArray("messages");
      ObjectNode msg = messages.addObject();
      msg.put("role", "user");
      // Anthropic supports a top-level system field,
      // but to keep the prompt structure identical to
      // OpenAI we concatenate system + user into a
      // single user message.
      msg.put("content", system + "\n\n" + user);
    } else {
      ArrayNode messages = root.putArray("messages");
      ObjectNode sysMsg = messages.addObject();
      sysMsg.put("role", "system");
      sysMsg.put("content", system);
      ObjectNode userMsg = messages.addObject();
      userMsg.put("role", "user");
      userMsg.put("content", user);
      root.put("temperature", 0.0);
      // Per-model output cap. The default for unknown
      // models is 200K (matches Claude Sonnet/Opus /
      // o1 / MiniMax-M3). Models with smaller windows
      // (gtemp-3.5 = 4K, gpt-4 = 8K, etc.) get the
      // right value from LlmConnection.defaultMaxTokensFor.
      // Reasoning models: see note in buildToolRequest
      // about why the high cap matters (otherwise the
      // model spends the budget on thinking and never
      // emits a verdict).
      root.put("max_tokens", conn.effectiveMaxTokens());
      String modelLower = (conn.model() == null) ? "" : conn.model().toLowerCase();
      if (modelLower.contains("m3")
          || modelLower.contains("reasoning")
          || modelLower.contains("thinking")) {
        root.put("reasoning_effort", "low");
      }
    }
    return MAPPER.writeValueAsString(root);
  }

  private String buildToolRequest(
      List<LlmMessage> messages,
      List<ToolDefinition> tools,
      String toolChoice,
      String model,
      double temperature)
      throws Exception {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("model", model);
    if (conn.provider() == LlmConnection.Provider.ANTHROPIC) {
      // Anthropic's Messages API also supports
      // tool_use, but the request shape is different
      // (the `tools` field is a list of
      // {name, description, input_schema} objects
      // without the OpenAI `type/function` wrapper,
      // and the response has `content[]` blocks
      // instead of `message.tool_calls`). We
      // intentionally support the OpenAI shape
      // uniformly and ask Anthropic users to use
      // a compatible proxy (LiteLLM et al.). This
      // keeps the agent code free of vendor-
      // specific parsing.
      throw new UnsupportedOperationException(
          "Anthropic native tool-calling not yet supported "
              + "in plumb-ai; use an OpenAI-compatible proxy.");
    }
    ArrayNode msgs = root.putArray("messages");
    for (LlmMessage m : messages) {
      ObjectNode mn = msgs.addObject();
      mn.put("role", m.role());
      if ("tool".equals(m.role())) {
        mn.put("tool_call_id", m.toolCallId());
        mn.put("content", m.content() == null ? "" : m.content());
        continue;
      }
      if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
        ArrayNode tcs = mn.putArray("tool_calls");
        for (ToolCall tc : m.toolCalls()) {
          ObjectNode tcn = tcs.addObject();
          tcn.put("id", tc.id());
          tcn.put("type", "function");
          ObjectNode fn = tcn.putObject("function");
          fn.put("name", tc.name());
          // OpenAI wants the arguments as a JSON
          // string. Jackson serialises the JsonNode
          // back to a string.
          fn.put("arguments", MAPPER.writeValueAsString(tc.args()));
        }
      }
      if (m.content() != null) {
        mn.put("content", m.content());
      }
    }
    if (!tools.isEmpty()) {
      ArrayNode ts = root.putArray("tools");
      for (ToolDefinition t : tools) {
        ts.add(t.toJsonNode(MAPPER));
      }
      // toolChoice comes from the agent: "auto" (default,
      // model decides), "required" (force at least one
      // tool call this turn), or "none" (no tools).
      // The agent uses "required" on the first turn to
      // stop the model from skipping navigation
      // (finding: model was treating 60% of cases
      // as "trivial" and answering without any tool).
      root.put("tool_choice", toolChoice);
    }
    root.put("temperature", temperature);
    // Per-model output cap. The lookup in
    // LlmConnection.defaultMaxTokensFor returns the
    // right value for known models (200K for Claude
    // Sonnet/Opus / o1 / MiniMax-M3, 16K for GPT-4o,
    // 4K for GPT-3.5, etc.); unknown models default to
    // 200K. Reasoning models (MiniMax-M3, Claude
    // thinking, o1) need a HIGH cap — otherwise the
    // model spends the entire budget on the <think>
    // reasoning block and never emits a verdict. The
    // reasoning_effort knob below keeps the thinking
    // portion bounded so the verdict actually fits.
    root.put("max_tokens", conn.effectiveMaxTokens());
    String modelLower = (conn.model() == null) ? "" : conn.model().toLowerCase();
    if (modelLower.contains("m3")
        || modelLower.contains("reasoning")
        || modelLower.contains("thinking")) {
      root.put("reasoning_effort", "low");
    }
    return MAPPER.writeValueAsString(root);
  }

  // ─── response parsing ────────────────────────────────

  /** Pull the assistant's text reply out of either schema. */
  private String extractText(String body) throws Exception {
    JsonNode root = MAPPER.readTree(body);
    String text;
    if (conn.provider() == LlmConnection.Provider.ANTHROPIC) {
      // Anthropic: content[0].text
      JsonNode content = root.path("content");
      if (!content.isArray() || content.isEmpty()) return null;
      text = content.get(0).path("text").asText("");
    } else {
      // OpenAI: choices[0].message.content
      text = root.path("choices").path(0).path("message").path("content").asText("");
    }
    text = stripThinkBlocks(text);
    text = stripFence(text);
    return text.isEmpty() ? null : text;
  }

  /**
   * Parse the tool-calling reply. Returns one LlmReply with the assistant's text (may be empty) and
   * any tool calls the model requested.
   */
  private LlmReply parseToolReply(String body) throws Exception {
    JsonNode root = MAPPER.readTree(body);
    JsonNode message = root.path("choices").path(0).path("message");
    String content = stripThinkBlocks(stripFence(message.path("content").asText("")));
    String finishReason = root.path("choices").path(0).path("finish_reason").asText("stop");
    List<ToolCall> calls = new ArrayList<>();
    JsonNode tcs = message.path("tool_calls");
    if (tcs.isArray()) {
      for (JsonNode tn : tcs) {
        String id = tn.path("id").asText("");
        String name = tn.path("function").path("name").asText("");
        // OpenAI returns `arguments` as a JSON
        // string; parse it into a tree.
        String argsStr = tn.path("function").path("arguments").asText("{}");
        JsonNode args;
        try {
          args = MAPPER.readTree(argsStr);
        } catch (Exception e) {
          // The model sometimes emits malformed
          // JSON; treat as empty object so the
          // tool can return a friendly error.
          args = MAPPER.createObjectNode();
        }
        calls.add(new ToolCall(id, name, args));
      }
    }
    return new LlmReply(content, calls, finishReason);
  }

  private static String stripFence(String text) {
    if (text == null) return "";
    text = text.trim();
    if (text.startsWith("```")) {
      int firstNewline = text.indexOf('\n');
      if (firstNewline > 0) text = text.substring(firstNewline + 1);
      if (text.endsWith("```")) {
        text = text.substring(0, text.length() - 3);
      }
      text = text.trim();
    }
    return text;
  }

  /**
   * Strip {@code <think>...</think>} blocks emitted by reasoning models (e.g. MiniMax-M3) so the
   * rest of the pipeline sees a clean assistant message.
   *
   * <p>Some models (e.g. Anthropic's Claude with "thinking" enabled, MiniMax-M3 with reasoning on)
   * prepend a hidden CoT block to the content before the real answer. The Re-Act loop and the
   * {@code "FINAL:"} prefix detector would otherwise miss the verdict because the content starts
   * with {@code <think>}. This helper removes both the single-line and multi-line variants, plus
   * any leading whitespace after the close tag.
   */
  private static String stripThinkBlocks(String text) {
    if (text == null || text.isEmpty()) return text;
    // Non-greedy match: stops at the FIRST closing
    // tag. Reasoning models emit exactly one block
    // per turn, so this is correct.
    java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?is)<think>.*?</think>\\s*");
    java.util.regex.Matcher m = p.matcher(text);
    if (m.find()) {
      return m.replaceAll("").trim();
    }
    return text;
  }

  private static String truncate(String s, int n) {
    if (s == null) return "";
    return s.length() <= n ? s : s.substring(0, n) + "...";
  }
}

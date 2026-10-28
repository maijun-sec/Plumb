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

/**
 * One tool call the LLM wants the agent to execute. Matches OpenAI's {@code
 * choices[0].message.tool_calls[i]} shape:
 *
 * <pre>{@code
 * {
 *   "id": "call_abc123",
 *   "type": "function",
 *   "function": {
 *     "name": "read_source",
 *     "arguments": "{\"path\":\"/src/User.java\",\"start\":30,\"end\":80}"
 *   }
 * }
 * }</pre>
 *
 * <p>The {@link #id} is required so the {@code "tool"} role reply can be paired with the
 * originating assistant turn (OpenAI uses {@code tool_call_id} for the same purpose).
 *
 * <p>The {@link #args} is a parsed JSON object, not a raw string. {@code AIProvider.chatWithTools}
 * parses the model's JSON-string argument into a tree before constructing this record, so tools see
 * a real {@link JsonNode} they can navigate with {@code .path("path").asText()}.
 */
public record ToolCall(String id, String name, JsonNode args) {
  public ToolCall {
    if (args == null) {
      // Tolerate missing arguments (some models emit "{}" or
      // null when the tool has no required parameters).
      args = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    }
  }
}

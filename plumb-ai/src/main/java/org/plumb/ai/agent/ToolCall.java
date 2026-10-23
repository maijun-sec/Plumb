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

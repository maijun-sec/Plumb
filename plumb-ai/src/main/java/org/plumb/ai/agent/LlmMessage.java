package org.plumb.ai.agent;

import java.util.List;

/**
 * One message in a chat-completion conversation. Five roles (matches OpenAI's chat-completion
 * schema):
 *
 * <ul>
 *   <li>{@code system} ? role priming (set once at the start)
 *   <li>{@code user} ? the actual question (set once at the start, and again as the "user" turn the
 *       model sees between tool-call iterations if needed)
 *   <li>{@code assistant} ? the model's reply (may carry {@link #toolCalls} and may also carry
 *       {@link #content} when the model produces both a message and a tool call in the same turn)
 *   <li>{@code tool} ? the result of one tool execution, paired with the {@code tool_call_id} from
 *       the assistant turn that requested it
 * </ul>
 *
 * <p>The class is deliberately a plain record ? Jackson serialises it to JSON in {@code
 * AIProvider.chatWithTools}, and the JSON shape is exactly what OpenAI's function-calling API
 * expects. No Spring-AI, no agent framework.
 */
public record LlmMessage(
        String role,
        String content,
        String toolCallId, // non-null when role == "tool"
        String name, // non-null when role == "tool"
        List<ToolCall> toolCalls // non-null when role == "assistant"
) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, null, null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, null, null);
    }

    public static LlmMessage assistant(String content, List<ToolCall> toolCalls) {
        return new LlmMessage(
                "assistant", content, null, null, toolCalls == null ? List.of() : toolCalls);
    }

    public static LlmMessage tool(String toolCallId, String name, String content) {
        return new LlmMessage("tool", content, toolCallId, name, null);
    }
}

package org.plumb.ai.agent;

/**
 * One tool execution result. The agent sends this back to the LLM as a {@code "tool"} role
 * message (paired with the originating tool call's {@code id}).
 *
 * <p>Two flavours:
 *
 * <ul>
 *   <li>{@link #ok(String)} ? tool ran successfully; {@code body} is the human-readable result
 *       (plain text or short JSON).
 *   <li>{@link #error(String)} ? tool failed; the LLM sees the error message in the {@code "tool"}
 *       reply and can adjust (e.g. try a different path, or conclude that the information isn't
 *       available).
 * </ul>
 *
 * <p>The {@code body} is rendered into the chat-completion request as a string. Tools that produce
 * structured data (e.g. a list of callers) should pre-serialise to JSON before returning.
 */
public record ToolResult(boolean ok, String body) {

    public static ToolResult ok(String body) {
        return new ToolResult(true, body == null ? "" : body);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, "error: " + (message == null ? "(no message)" : message));
    }

    /**
     * Format for the OpenAI {@code "tool"} role content.
     */
    public String render() {
        return body == null ? "" : body;
    }
}

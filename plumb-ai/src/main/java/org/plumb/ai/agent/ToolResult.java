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
 * One tool execution result. The agent sends this back to the LLM as a {@code "tool"} role message
 * (paired with the originating tool call's {@code id}).
 *
 * <p>Two flavours:
 *
 * <ul>
 *   <li>{@link #ok(String)} -- tool ran successfully; {@code body} is the human-readable result
 *       (plain text or short JSON).
 *   <li>{@link #error(String)} -- tool failed; the LLM sees the error message in the {@code "tool"}
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

  /** Format for the OpenAI {@code "tool"} role content. */
  public String render() {
    return body == null ? "" : body;
  }
}

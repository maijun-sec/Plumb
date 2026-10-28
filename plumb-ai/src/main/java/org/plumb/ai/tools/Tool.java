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
package org.plumb.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.plumb.ai.agent.ToolDefinition;
import org.plumb.ai.agent.ToolResult;

/**
 * One tool the Re-Act agent exposes to the LLM. Each tool has:
 *
 * <ul>
 *   <li>A stable {@link #name()} the LLM echoes in {@code tool_calls[].function.name}.
 *   <li>A short {@link #description()} the LLM uses to decide when to call the tool (this is the
 *       most important field for shaping agent behaviour -- a vague description produces vague tool
 *       use).
 *   <li>A {@link #definition()} the agent sends to the LLM as the JSON Schema for the tool's
 *       parameters.
 *   <li>An {@link #execute(ToolContext, JsonNode)} method the agent calls with the LLM's parsed
 *       argument object. Returns a {@link ToolResult} the agent feeds back as a {@code "tool"} role
 *       message.
 * </ul>
 *
 * <p>Tools are stateless across invocations -- all state (file cache, current finding, call graph)
 * lives in the {@link ToolContext} passed in. This makes tools safe to register in a singleton
 * {@code ToolRegistry} and reuse across many agent runs.
 */
public interface Tool {

  /**
   * Stable tool id. Used as the OpenAI function name and in {@code tool_calls[].function.name}.
   * Must be unique within a {@link ToolRegistry}.
   */
  String name();

  /** Human prose. The LLM uses this to decide when to call the tool. Keep it short and concrete. */
  String description();

  /**
   * The JSON Schema the LLM uses to construct the arguments object. Built once at registration time
   * and cached.
   */
  ToolDefinition definition();

  /**
   * Run the tool. The {@code args} is a parsed JSON object (Jackson {@link JsonNode}); tool
   * implementations navigate it with {@code args.path("foo").asText()}.
   *
   * @return a {@link ToolResult.ok}/{@link ToolResult.error} that the agent will feed back to the
   *     LLM.
   */
  ToolResult execute(ToolContext ctx, JsonNode args);
}

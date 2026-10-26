package org.plumb.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.plumb.ai.agent.ToolDefinition;
import org.plumb.ai.agent.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Name → {@link Tool} map. The Re-Act agent looks up a tool by name when the LLM emits a tool call,
 * executes it under the call's {@link ToolContext}, and returns the result back to the LLM.
 *
 * <p>Two things the registry owns:
 *
 * <ol>
 *   <li>The tool dispatch table. {@link #execute(String, ToolContext, JsonNode)} looks up the tool
 *       by name and runs it. A missing tool returns {@link ToolResult#error(String)} so the LLM can
 *       recover (or the runner can declare "max-turns exceeded" on the next iteration).
 *   <li>The tool definitions for the LLM. {@link #definitions()} returns the list of {@link
 *       ToolDefinition} the agent ships in the {@code tools} field of the chat-completion request.
 * </ol>
 *
 * <p>Tools are registered at startup and never removed. The registry is thread-safe (the underlying
 * {@link LinkedHashMap} is used under the per-thread {@code Agent} runner's single-threaded
 * tool-dispatch loop; the LLM doesn't issue parallel tool calls in one turn, so we don't need a
 * concurrent map).
 */
public class ToolRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

  private final Map<String, Tool> tools = new LinkedHashMap<>();

  public ToolRegistry() {}

  public ToolRegistry register(Tool tool) {
    Objects.requireNonNull(tool, "tool");
    if (tools.containsKey(tool.name())) {
      throw new IllegalArgumentException("Duplicate tool name: " + tool.name());
    }
    tools.put(tool.name(), tool);
    return this;
  }

  public Tool get(String name) {
    return tools.get(name);
  }

  /** Names of every registered tool, in registration order. */
  public List<String> toolNames() {
    return new ArrayList<>(tools.keySet());
  }

  /**
   * Build the {@code tools} field of the OpenAI request body -- one {@link ToolDefinition} per
   * registered tool, in registration order.
   */
  public List<ToolDefinition> definitions() {
    List<ToolDefinition> out = new ArrayList<>(tools.size());
    for (Tool t : tools.values()) out.add(t.definition());
    return out;
  }

  /**
   * Look up a tool by name and run it. Returns {@link ToolResult#error(String)} if the tool is not
   * registered (so the LLM can adjust), or if the tool throws an exception (logged at WARN).
   */
  public ToolResult execute(String name, ToolContext ctx, JsonNode args) {
    Tool tool = tools.get(name);
    if (tool == null) {
      return ToolResult.error("unknown tool: " + name + " (registered: " + toolNames() + ")");
    }
    try {
      return tool.execute(ctx, args);
    } catch (Exception e) {
      LOG.warn("Tool {} threw: {}", name, e.toString());
      return ToolResult.error("exception in " + name + ": " + e.getMessage());
    }
  }
}

package org.plumb.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.plumb.ai.agent.ToolDefinition;
import org.plumb.ai.agent.ToolResult;

/**
 * read a range of source lines. The LLM uses this to look at a full method (not just the ±5 line
 * window the initial finding carries), read a class to spot a missing annotation, or inspect a
 * caller to verify it sanitises before passing data on.
 *
 * <p>Arguments:
 *
 * <ul>
 *   <li>{@code path} (string, required) -- absolute or relative to the scan's source root.
 *   <li>{@code start} (integer, optional, default 1) -- 1-based first line to return.
 *   <li>{@code end} (integer, optional, default 200) -- 1-based last line to return (inclusive).
 *   <li>{@code max_lines} (integer, optional, default 200) -- hard cap on the response size; we
 *       slice if the user asks for more, so a runaway LLM can't pull a 100k-line file in one call.
 * </ul>
 *
 * <p>Returns the lines in the form {@code "L<line>: <code>"}, one per line, so the LLM can read
 * both content and original line number.
 */
public class ReadSourceTool implements Tool {

  @Override
  public String name() {
    return "read_source";
  }

  @Override
  public String description() {
    return "Read a range of source lines from a file. "
        + "Use this to look at a full method (not just the "
        + "lines around the finding), inspect a caller for "
        + "guards, or read a class to spot a missing annotation.";
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        name(),
        description(),
        Map.of(
            "path",
            Map.of(
                "type",
                "string",
                "description",
                "File path, absolute or relative to the source root."),
            "start",
            Map.of("type", "integer", "description", "1-based first line to return. Default 1."),
            "end",
            Map.of(
                "type",
                "integer",
                "description",
                "1-based last line to return (inclusive). Default 200."),
            "max_lines",
            Map.of(
                "type",
                "integer",
                "description",
                "Hard cap on the number of lines returned. Default 200.")),
        List.of("path"));
  }

  @Override
  public ToolResult execute(ToolContext ctx, JsonNode args) {
    if (args == null || !args.has("path") || args.get("path").asText().isEmpty()) {
      return ToolResult.error("missing required argument: path");
    }
    String path = args.get("path").asText();
    int start = args.has("start") ? args.get("start").asInt(1) : 1;
    int end = args.has("end") ? args.get("end").asInt(200) : 200;
    int maxLines = args.has("max_lines") ? args.get("max_lines").asInt(200) : 200;
    byte[] bytes = ctx.readFileBytes(path);
    if (bytes == null) {
      return ToolResult.error("cannot read file: " + path);
    }
    String content = new String(bytes, StandardCharsets.UTF_8);
    // Split on \n preserving line numbers. 1-based.
    String[] allLines = content.split("\n", -1);
    int from = Math.max(1, start);
    int to = Math.min(allLines.length, end);
    int cap = Math.max(1, maxLines);
    if (to - from + 1 > cap) to = from + cap - 1;
    StringBuilder sb = new StringBuilder(cap * 80);
    for (int i = from; i <= to; i++) {
      sb.append("L").append(i).append(": ").append(allLines[i - 1]).append('\n');
    }
    sb.append("(file has ")
        .append(allLines.length)
        .append(" lines total; ")
        .append("showing ")
        .append(from)
        .append("..")
        .append(to)
        .append(")");
    return ToolResult.ok(sb.toString());
  }
}

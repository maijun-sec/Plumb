package org.plumb.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.plumb.ai.agent.ToolDefinition;
import org.plumb.ai.agent.ToolResult;

/**
 * list a directory's contents. The "Claude Code-style" file-system browsing tool: when the LLM
 * doesn't know where a class lives, {@code list_dir} shows the package layout and {@link GlobTool}
 * zeros in on the file.
 *
 * <p>Arguments:
 *
 * <ul>
 *   <li>{@code path} (string, optional) ? directory to list. Default = source root.
 *   <li>{@code max_depth} (integer, optional, default 1) ? recursion depth. 0 = just this dir, 1 =
 *       this + immediate children, etc. Cap 3.
 *   <li>{@code max_entries} (integer, optional, default 50) ? cap on returned entries.
 * </ul>
 *
 * <p>Returns one line per entry: {@code "<kind> <name>"} where {@code kind} is {@code "[D]"} for
 * directory or {@code "[F]"} for file. Sorted alphabetically. Skips noisy build dirs.
 */
public class ListDirTool implements Tool {

  private static final int DEFAULT_DEPTH = 1;
  private static final int MAX_DEPTH_CAP = 3;
  private static final int DEFAULT_MAX = 50;
  private static final int HARD_CAP = 200;

  @Override
  public String name() {
    return "list_dir";
  }

  @Override
  public String description() {
    return "List a directory's contents. Returns one line "
        + "per entry: '[D] <dirname>' or '[F] <filename>'. "
        + "Use max_depth to recurse (0=just this dir, "
        + "1=this+children). Skips build/, target/, "
        + ".git/, etc.";
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        name(),
        description(),
        Map.of(
            "path",
            Map.of("type", "string", "description", "Directory to list. Default = source root."),
            "max_depth",
            Map.of("type", "integer", "description", "Recursion depth, 0..3. Default 1."),
            "max_entries",
            Map.of(
                "type", "integer", "description", "Cap on returned entries. Default 50, max 200.")),
        List.of());
  }

  @Override
  public ToolResult execute(ToolContext ctx, JsonNode args) {
    int maxDepth =
        Math.min(
            MAX_DEPTH_CAP,
            args != null && args.has("max_depth")
                ? args.get("max_depth").asInt(DEFAULT_DEPTH)
                : DEFAULT_DEPTH);
    int max =
        Math.min(
            HARD_CAP,
            args != null && args.has("max_entries")
                ? args.get("max_entries").asInt(DEFAULT_MAX)
                : DEFAULT_MAX);
    Path dir = ctx.resolvePath(args != null && args.has("path") ? args.get("path").asText() : "");
    if (dir == null || !Files.isDirectory(dir)) {
      return ToolResult.error("path is not a directory: " + dir);
    }
    List<String> out = new ArrayList<>(max);
    try {
      collect(dir, 0, maxDepth, out, max);
    } catch (IOException e) {
      return ToolResult.error("list_dir failed: " + e.getMessage());
    }
    if (out.isEmpty()) {
      return ToolResult.ok("(empty directory: " + dir + ")");
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Contents of ").append(dir).append(" (depth ").append(maxDepth).append("):\n");
    for (String e : out) sb.append(e).append('\n');
    if (out.size() >= max) {
      sb.append("(truncated to ").append(max).append(")");
    }
    return ToolResult.ok(sb.toString());
  }

  private static void collect(Path dir, int depth, int maxDepth, List<String> out, int max)
      throws IOException {
    if (out.size() >= max) return;
    if (depth > maxDepth) return;
    // One level of Files.list — non-recursive at depth 0
    // of the recursion, then recurse.
    try (Stream<Path> kids = Files.list(dir)) {
      List<Path> sorted = new java.util.ArrayList<>();
      kids.forEach(sorted::add);
      sorted.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
      for (Path p : sorted) {
        if (out.size() >= max) return;
        String name = p.getFileName().toString();
        if (depth == 0 && isSkippedDir(p)) continue;
        String kind = Files.isDirectory(p) ? "[D]" : "[F]";
        out.add("  ".repeat(depth) + kind + " " + name);
      }
    }
    if (depth < maxDepth) {
      try (Stream<Path> kids = Files.list(dir)) {
        for (Path p : (Iterable<Path>) kids::iterator) {
          if (out.size() >= max) return;
          if (!Files.isDirectory(p)) continue;
          if (isSkippedDir(p)) continue;
          collect(p, depth + 1, maxDepth, out, max);
        }
      }
    }
  }

  private static boolean isSkippedDir(Path file) {
    String name = file.getFileName() == null ? "" : file.getFileName().toString();
    return name.equals(".git")
        || name.equals("build")
        || name.equals("target")
        || name.equals("node_modules")
        || name.equals(".gradle")
        || name.equals(".idea")
        || name.equals("dist")
        || name.equals("out");
  }
}

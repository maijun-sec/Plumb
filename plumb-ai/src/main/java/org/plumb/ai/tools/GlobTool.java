package org.plumb.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.plumb.ai.agent.ToolDefinition;
import org.plumb.ai.agent.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * find files by glob. The {@code "find the test file for this class"} companion to
 * {@link GrepTool}: works on raw file paths, no AST / TAC required.
 *
 * <p>Arguments:
 *
 * <ul>
 *   <li>{@code pattern} (string, required) ? glob pattern. Supports {@code *}, {@code ?}, and
 *       {@code **}. Example: {@code "**\/UserService*.java"}.
 *   <li>{@code base_dir} (string, optional) ? directory to search. Default = source root.
 *   <li>{@code max_results} (integer, optional, default 50) ? hard cap on returned paths.
 * </ul>
 *
 * <p>Returns the matching paths (one per line, relative to {@code base_dir} when possible), or a
 * friendly "(no matches)" line when nothing matched.
 */
public class GlobTool implements Tool {

    private static final int DEFAULT_MAX = 50;
    private static final int HARD_CAP = 500;

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Find files by glob. Supports *, ?, and **. "
                + "Use this to locate the file that defines a "
                + "class, the test file for a class, or all "
                + "files matching a build tag. Returns "
                + "matching paths relative to base_dir.";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                name(),
                description(),
                Map.of(
                        "pattern",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Glob pattern, e.g. '**\\/User*.java' or '*.xml'."),
                        "base_dir",
                        Map.of(
                                "type", "string", "description", "Directory to search. Default = source root."),
                        "max_results",
                        Map.of(
                                "type",
                                "integer",
                                "description",
                                "Cap on returned paths. Default 50, max 500.")),
                List.of("pattern"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode args) {
        if (args == null || !args.has("pattern") || args.get("pattern").asText().isEmpty()) {
            return ToolResult.error("missing required argument: pattern");
        }
        String patternStr = args.get("pattern").asText();
        int max =
                Math.min(
                        HARD_CAP,
                        args.has("max_results") ? args.get("max_results").asInt(DEFAULT_MAX) : DEFAULT_MAX);
        Path base = ctx.resolvePath(args.has("base_dir") ? args.get("base_dir").asText() : "");
        if (base == null || !Files.isDirectory(base)) {
            return ToolResult.error("base_dir is not a directory: " + base);
        }
        GrepTool.GlobMatcher matcher = new GrepTool.GlobMatcher(patternStr);
        List<String> hits = new ArrayList<>(max);
        try (var stream = Files.walk(base)) {
            for (var p : (Iterable<Path>) stream::iterator) {
                if (hits.size() >= max) break;
                if (!Files.isRegularFile(p)) continue;
                if (isSkippedDir(p)) continue;
                if (matcher.matches(p, base)) {
                    hits.add(relativize(p, base));
                }
            }
        } catch (Exception e) {
            return ToolResult.error("glob failed: " + e.getMessage());
        }

        if (hits.isEmpty()) {
            return ToolResult.ok("(no files matching " + patternStr + " under " + base + ")");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Found ")
                .append(hits.size())
                .append(" file(s) matching ")
                .append(patternStr)
                .append(":\n");
        for (String h : hits) sb.append(h).append('\n');
        if (hits.size() >= max) {
            sb.append("(truncated to ").append(max).append("; raise max_results to see more)");
        }
        return ToolResult.ok(sb.toString());
    }

    private static String relativize(Path file, Path base) {
        try {
            return base.toRealPath().relativize(file.toRealPath()).toString();
        } catch (Exception e) {
            return file.getFileName().toString();
        }
    }

    private static boolean isSkippedDir(Path file) {
        for (Path p = file; p != null; p = p.getParent()) {
            String name = p.getFileName() == null ? "" : p.getFileName().toString();
            if (name.equals(".git")
                    || name.equals("build")
                    || name.equals("target")
                    || name.equals("node_modules")
                    || name.equals(".gradle")
                    || name.equals(".idea")
                    || name.equals("dist")
                    || name.equals("out")) {
                return true;
            }
        }
        return false;
    }
}

package org.plumb.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.plumb.ai.agent.ToolDefinition;
import org.plumb.ai.agent.ToolResult;

import java.util.*;

/**
 * R-? Step 3 ? return the full call hierarchy of a method: who calls it (callers), and
 * (transitively) who calls them. Walks {@link FindCallersTool} until the caller set stabilises or
 * the depth cap is hit.
 *
 * <p>This is the tool the LLM uses to verify a finding's "this is reachable from public HTTP" claim
 * — a SQL injection in a private helper is much less severe than one reachable from a public
 * {@code @PostMapping} entry-point. The LLM walks up the call chain to see whether the public
 * surface reaches the sink.
 *
 * <p>Two paths:
 *
 * <ul>
 *   <li><b>IR path</b> — when the context's {@code program} is a {@code TacProgram}, the tool
 *       iterates every function and looks for {@code CALL} instructions targeting the query method
 *       (precise).
 *   <li><b>Regex fallback</b> — same logic as {@link FindCallersTool}'s fallback, but invoked
 *       recursively per caller. Always available, less precise.
 * </ul>
 */
public class GetCallHierarchyTool implements Tool {

    private static final int MAX_DEPTH = 4;
    private static final int MAX_NODES = 100;

    @Override
    public String name() {
        return "get_call_hierarchy";
    }

    @Override
    public String description() {
        return "Return the full call hierarchy of a method: "
                + "who calls it (callers), and transitively who "
                + "calls them. Walks find_callers recursively "
                + "until the call set stabilises or depth "
                + MAX_DEPTH
                + " is hit. Returns a tree of 'method "
                + "<- caller_a <- caller_b' chains.";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                name(),
                description(),
                Map.of(
                        "method",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Method to walk the call " + "hierarchy of. Simple or FQN."),
                        "max_depth",
                        Map.of(
                                "type",
                                "integer",
                                "description",
                                "Recursion cap. Default " + MAX_DEPTH + ", max 6.")),
                List.of("method"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode args) {
        if (args == null || !args.has("method") || args.get("method").asText().isEmpty()) {
            return ToolResult.error("missing required argument: method");
        }
        String method = args.get("method").asText();
        int maxDepth =
                Math.min(6, args.has("max_depth") ? args.get("max_depth").asInt(MAX_DEPTH) : MAX_DEPTH);

        StringBuilder sb = new StringBuilder();
        sb.append("Call hierarchy rooted at ").append(method).append(":\n\n");
        Set<String> visited = new HashSet<>();
        visited.add(method);
        // BFS so the output is layered (depth 1 callers, then
        // depth 2 callers of those, etc.).
        Deque<Frame> queue = new ArrayDeque<>();
        queue.add(new Frame(method, 0));
        int nodes = 0;
        while (!queue.isEmpty() && nodes < MAX_NODES) {
            Frame frame = queue.poll();
            if (frame.depth >= maxDepth) {
                sb.append(indent(frame.depth))
                        .append("(depth cap reached at ")
                        .append(frame.method)
                        .append(")\n");
                continue;
            }
            List<String> callers = findCallers(frame.method, ctx);
            nodes += callers.size();
            if (callers.isEmpty()) {
                sb.append(indent(frame.depth)).append(frame.method).append(" (no callers)\n");
            } else {
                for (String c : callers) {
                    sb.append(indent(frame.depth)).append(frame.method).append(" <- ").append(c).append('\n');
                    if (visited.add(c)) {
                        queue.add(new Frame(c, frame.depth + 1));
                    }
                }
            }
        }
        if (nodes >= MAX_NODES) {
            sb.append("\n(truncated at ")
                    .append(MAX_NODES)
                    .append(" total nodes; raise the per-tool cap)");
        }
        return ToolResult.ok(sb.toString());
    }

    /**
     * Find immediate callers of {@code method}. Wraps
     * {@link FindCallersTool}'s body shape but returns
     * the caller method / function name. The FindCallersTool
     * output has two shapes:
     * <p>
     * TAC path:  "L<line> in <fn>: CALL <sig>"
     * Grep path: "L<line> in <rel-path>: <code>"
     * <p>
     * In the grep path the "in" location is a file path,
     * not a function name. We detect the shape and
     * extract the function name from the line text when
     * possible, falling back to the path component.
     */
    private static List<String> findCallers(String method, ToolContext ctx) {
        FindCallersTool fc = new FindCallersTool();
        com.fasterxml.jackson.databind.node.ObjectNode args =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        args.put("method", method);
        args.put("max", 30);
        ToolResult r = fc.execute(ctx, args);
        if (!r.ok() || r.body() == null || r.body().isEmpty()) return List.of();
        // Each line is "L<line> in <fn>: CALL <sig>" (TAC) or
        // "L<line> in <rel-path>: <code>" (grep).
        List<String> callers = new ArrayList<>();
        for (String line : r.body().split("\n")) {
            int in = line.indexOf(" in ");
            int colon = line.indexOf(':', in);
            if (in < 0 || colon < 0) continue;
            String location = line.substring(in + 4, colon);
            String callerFn = extractFnFromLocation(location, line);
            if (callerFn == null || callerFn.isEmpty()) continue;
            if (!callers.contains(callerFn)) callers.add(callerFn);
        }
        return callers;
    }

    /**
     * Convert a FindCallersTool "in" location into a function name. The location is a function name
     * in the TAC path and a file path in the grep path. We try to extract the function name from the
     * actual line text (after the colon ? the original code line); failing that, we return the file
     * basename.
     */
    private static String extractFnFromLocation(String location, String line) {
        // The location looks like:
        //   TAC:  "FooController.create" or "com.example.Foo.create"
        //   grep: "src/main/java/com/example/FooController.java"
        // If it contains a '.', it might be a class.method —
        // return the whole thing (the LLM gets a precise
        // method handle).
        if (!location.endsWith(".java") && location.contains(".")) {
            return location;
        }
        // If it ends with .java, the location is a file path.
        // The actual function name is in the line text
        // after the colon. Try to find "methodName(" after
        // the colon and return it.
        int colon = line.indexOf(':', line.indexOf(" in "));
        if (colon < 0) return fileBase(location);
        String tail = line.substring(colon + 1).trim();
        // Look for "<word>(" — that's the call expression.
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").matcher(tail);
        if (m.find()) return m.group(1);
        return fileBase(location);
    }

    private static String fileBase(String path) {
        if (path == null || path.isEmpty()) return path;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String base = slash < 0 ? path : path.substring(slash + 1);
        if (base.endsWith(".java")) base = base.substring(0, base.length() - 5);
        return base;
    }

    private static String indent(int depth) {
        return "  ".repeat(Math.min(depth, 8));
    }

    private record Frame(String method, int depth) {
    }
}

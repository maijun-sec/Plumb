package org.plumb.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.plumb.ai.agent.ToolDefinition;
import org.plumb.ai.agent.ToolResult;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * find every call site of a method across the program. The LLM uses this to verify that
 * callers don't sanitise the argument before passing it in (or to spot a single test-fixture caller
 * that explains why the static rule fired in a non-exploitable context).
 *
 * <p>Arguments:
 *
 * <ul>
 *   <li>{@code method} (string, required) ? the simple method name (e.g. {@code "executeQuery"}) or
 *       the fully-qualified callee signature (e.g. {@code "java.sql.Statement.executeQuery"}).
 *       Either form is matched.
 *   <li>{@code max} (integer, optional, default 20) ? hard cap on returned call sites, so the LLM
 *       doesn't get a 100-call-site dump.
 * </ul>
 *
 * <p>Returns one line per call site: {@code "L<line> in <function>: CALL <sig>"}.
 */
public class FindCallersTool implements Tool {

    @Override
    public String name() {
        return "find_callers";
    }

    @Override
    public String description() {
        return "Find every call site of a method across the "
                + "scanned program. Uses the TAC call graph when "
                + "available (walks every function's instructions "
                + "and finds CALL instructions whose calleeSig "
                + "matches the query — FQN or simple name) and "
                + "falls back to a raw-text regex grep when no "
                + "TAC program is attached to the context (no "
                + "AST/TAC required). Accepts either a simple "
                + "method name (\"executeQuery\") or a full callee "
                + "signature (\"java.sql.Statement.executeQuery\").";
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
                                "Method name or full callee signature to look for."),
                        "max",
                        Map.of(
                                "type",
                                "integer",
                                "description",
                                "Maximum number of call sites to return. Default 20.")),
                List.of("method"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode args) {
        if (args == null || !args.has("method")) {
            return ToolResult.error("missing required argument: method");
        }
        String method = args.get("method").asText();
        int max = args.has("max") ? args.get("max").asInt(20) : 20;
        if (method.isEmpty()) {
            return ToolResult.error("empty method name");
        }
        TacProgram program = programOf(ctx);
        if (program != null) {
            // Precise path: walk the TAC call graph.
            String simple = simpleNameOf(method);
            List<String> hits = new ArrayList<>();
            for (TacFunction fn : program.functions()) {
                for (TacInstruction ins : fn.instructions()) {
                    if (ins.kind != TacInstruction.Kind.CALL) continue;
                    if (ins.calleeSig == null) continue;
                    if (matches(ins.calleeSig, method, simple)) {
                        hits.add("L" + ins.line + " in " + fn.name() + ": CALL " + ins.calleeSig);
                        if (hits.size() >= max) break;
                    }
                }
                if (hits.size() >= max) break;
            }
            if (hits.isEmpty()) {
                return ToolResult.ok("(no call sites of " + method + " found in TAC)");
            }
            StringBuilder sb =
                    new StringBuilder("Found ")
                            .append(hits.size())
                            .append(" call site(s) of ")
                            .append(method)
                            .append(" (TAC):\n");
            for (String h : hits) sb.append(h).append('\n');
            if (hits.size() >= max) {
                sb.append("(truncated to ").append(max).append("; raise `max` to see more)");
            }
            return ToolResult.ok(sb.toString());
        }
        // Fallback path: regex grep. Looks for lines
        // containing the simple method name + "(".
        return grepFallback(ctx, method, max);
    }

    /**
     * Regex grep fallback used when no TAC program is attached. The LLM has the same answer ("every
     * call site of foo(...)") but sourced from raw text. Less precise (e.g. matches "foo" inside
     * comments or string literals) but always available.
     */
    private static ToolResult grepFallback(ToolContext ctx, String method, int max) {
        String simple = simpleNameOf(method);
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(simple) + "\\s*\\(");
        Path root = ctx.sourceRoot() == null ? Path.of(".") : Path.of(ctx.sourceRoot());
        if (!Files.exists(root)) {
            return ToolResult.ok("(no source root for grep fallback)");
        }
        List<String> hits = new ArrayList<>(max);
        try (var stream = Files.walk(root)) {
            for (var p : (Iterable<Path>) stream::iterator) {
                if (hits.size() >= max) break;
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (!name.endsWith(".java")
                        && !name.endsWith(".kt")
                        && !name.endsWith(".scala")
                        && !name.endsWith(".xml")
                        && !name.endsWith(".gradle")
                        && !name.endsWith(".properties")
                        && !name.endsWith(".yml")
                        && !name.endsWith(".yaml")) continue;
                if (isSkippedDir(p)) continue;
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    String[] lines = content.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (hits.size() >= max) break;
                        if (pattern.matcher(lines[i]).find()) {
                            String rel = relativize(p, ctx.sourceRoot());
                            hits.add("L" + (i + 1) + " in " + rel + ": " + lines[i].trim());
                        }
                    }
                } catch (Exception ignore) {
                    /* unreadable — skip */
                }
            }
        } catch (Exception e) {
            return ToolResult.error("grep fallback failed: " + e.getMessage());
        }
        if (hits.isEmpty()) {
            return ToolResult.ok("(no call sites of " + method + " found via grep)");
        }
        StringBuilder sb =
                new StringBuilder("Found ")
                        .append(hits.size())
                        .append(" call site(s) of ")
                        .append(method)
                        .append(" (grep fallback):\n");
        for (String h : hits) sb.append(h).append('\n');
        if (hits.size() >= max) {
            sb.append("(truncated to ").append(max).append(")");
        }
        return ToolResult.ok(sb.toString());
    }

    private static String relativize(Path file, String sourceRoot) {
        if (sourceRoot == null || sourceRoot.isEmpty()) return file.toString();
        try {
            Path root = Path.of(sourceRoot);
            return root.toRealPath().relativize(file.toRealPath()).toString();
        } catch (Exception e) {
            return file.getFileName().toString();
        }
    }

    private static boolean isSkippedDir(Path file) {
        for (Path p = file; p != null; p = p.getParent()) {
            String n = p.getFileName() == null ? "" : p.getFileName().toString();
            if (n.equals(".git")
                    || n.equals("build")
                    || n.equals("target")
                    || n.equals("node_modules")
                    || n.equals(".gradle")
                    || n.equals(".idea")
                    || n.equals("dist")
                    || n.equals("out")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A callee signature matches the query when:
     *
     * <ul>
     *   <li>the signatures are equal (exact match);
     *   <li>the query equals the signature's simple name (covers both the simple-name and FQN forms
     *       of the query against the simple form of the sig);
     *   <li>the query appears as the tail of the FQN ? i.e. preceded by {@code "."} (covers FQN
     *       queries like {@code "Statement.executeQuery"} matching {@code "java.sql.Statement.executeQuery"}).
     * </ul>
     *
     * <p>Previously the third clause was a bare {@code endsWith(query)}, which produced false
     * positives like {@code query="Foo"} matching {@code calleeSig="org.example.MyFoo"} ? see M1.
     * Requiring the {@code "."} separator makes the suffix match FQN-shaped only.
     */
    private static boolean matches(String calleeSig, String query, String querySimple) {
        if (calleeSig.equals(query)) return true;
        if (simpleNameOf(calleeSig).equals(querySimple)) return true;
        return calleeSig.endsWith("." + query);
    }

    private static String simpleNameOf(String sig) {
        if (sig == null) return "";
        int slash = sig.lastIndexOf('/');
        String tail = slash < 0 ? sig : sig.substring(0, slash);
        int dot = tail.lastIndexOf('.');
        return dot < 0 ? tail : tail.substring(dot + 1);
    }

    private static TacProgram programOf(ToolContext ctx) {
        Object p = ctx.program();
        return p instanceof TacProgram tp ? tp : null;
    }
}

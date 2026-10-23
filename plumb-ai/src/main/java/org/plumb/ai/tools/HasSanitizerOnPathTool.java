package org.plumb.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.plumb.ai.agent.ToolDefinition;
import org.plumb.ai.agent.ToolResult;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * check whether a tainted variable flows through any guard, sanitiser, or
 * assignment-to-constant between two line numbers. The single most useful tool for FP reduction: a
 * SQL_INJECTION finding where the variable is actually escaped by {@code setString()} in between is
 * a false positive; this tool tells the LLM that.
 *
 * <p>Heuristics:
 *
 * <ul>
 *   <li>A {@link TacInstruction.Kind#BRANCH} in the range that involves the variable (directly or
 *       via one MOVE hop) ? likely a guard.
 *   <li>A {@code CALL} to a known sanitiser method (escape family, validate family, setString
 *       family, etc.).
 *   <li>A {@code MOVE} that replaces the variable's RHS with a constant (e.g. {@code t_userId =
 *       ""}) ? usually a default / safe value.
 * </ul>
 *
 * <p>Returns the list of guard / sanitiser lines in the form {@code "L<line>: <kind> <details>"}.
 * Empty result means "no guard — variable flows unchecked".
 */
public class HasSanitizerOnPathTool implements Tool {

    /**
     * Method simple-names the tool recognises as sanitiser / guard helpers. Conservative ? we'd
     * rather miss a custom sanitiser than flag a real one as "no sanitiser". The user can extend this
     * list by editing the source.
     */
    private static final Set<String> KNOWN_SANITIZERS =
            Set.of(
                    // SQL injection
                    "setString",
                    "setObject",
                    "setInt",
                    "setLong",
                    "setNull",
                    "setBoolean",
                    "escapeSql",
                    "sanitize",
                    // XSS
                    "escapeHtml",
                    "escapeHtml4",
                    "escapeXml",
                    "encodeForHtml",
                    "sanitizeHtml",
                    // Path traversal
                    "normalize",
                    "toRealPath",
                    "toAbsolutePath",
                    "getCanonicalPath",
                    "isAbsolute",
                    // Command injection
                    "setCommand",
                    "setExecutable",
                    // Generic
                    "isValid",
                    "validate",
                    "checkInput",
                    "whitelist",
                    "allowList",
                    "escape",
                    "quote");

    @Override
    public String name() {
        return "has_sanitizer_on_path";
    }

    @Override
    public String description() {
        return "Check whether a tainted variable flows through any "
                + "guard, sanitiser, or assignment-to-constant between "
                + "two line numbers. Uses the TAC IR when available "
                + "(precise: tracks MOVE/BRANCH hops) and falls back "
                + "to a raw-text regex search when no TAC is attached. "
                + "Empty result means the variable flows unchecked.";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                name(),
                description(),
                Map.of(
                        "var", Map.of("type", "string", "description", "Tainted variable name to track."),
                        "from",
                        Map.of(
                                "type", "integer", "description", "1-based first line of the path to check."),
                        "to",
                        Map.of(
                                "type",
                                "integer",
                                "description",
                                "1-based last line of the path to check (inclusive).")),
                List.of("var", "from", "to"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode args) {
        if (args == null || !args.has("var") || !args.has("from") || !args.has("to")) {
            return ToolResult.error("missing required argument: var, from, to");
        }
        String var = args.get("var").asText();
        int from = args.get("from").asInt();
        int to = args.get("to").asInt();
        if (var.isEmpty()) {
            return ToolResult.error("empty var");
        }
        TacFunction fn = currentFunctionOf(ctx);
        if (fn != null) {
            // Precise path: walk the TAC IR.
            List<String> guards = new ArrayList<>();
            for (TacInstruction ins : fn.instructions()) {
                if (ins.line < from || ins.line > to) continue;
                if (ins.kind == TacInstruction.Kind.BRANCH && involvesVar(ins, var, fn)) {
                    guards.add(
                            "L"
                                    + ins.line
                                    + ": BRANCH on "
                                    + var
                                    + " (possible guard: "
                                    + (ins.lhs1 == null ? "?" : ins.lhs1.toString())
                                    + ")");
                } else if (ins.kind == TacInstruction.Kind.CALL
                        && ins.calleeSig != null
                        && KNOWN_SANITIZERS.contains(simpleName(ins.calleeSig))) {
                    guards.add("L" + ins.line + ": CALL " + ins.calleeSig + " (known sanitiser)");
                } else if (ins.kind == TacInstruction.Kind.MOVE
                        && ins.dest instanceof TacOperand.TacVar d
                        && var.equals(d.name())
                        && ins.lhs1 instanceof TacOperand.Const) {
                    guards.add("L" + ins.line + ": MOVE " + var + " = " + ins.lhs1 + " (assigned constant)");
                }
            }
            if (guards.isEmpty()) {
                return ToolResult.ok(
                        "(no guard / sanitiser for " + var + " between L" + from + " and L" + to + " in TAC)");
            }
            StringBuilder sb =
                    new StringBuilder("Found ")
                            .append(guards.size())
                            .append(" potential guard(s) for ")
                            .append(var)
                            .append(" between L")
                            .append(from)
                            .append(" and L")
                            .append(to)
                            .append(" (TAC):\n");
            for (String g : guards) sb.append(g).append('\n');
            return ToolResult.ok(sb.toString());
        }
        // Fallback: regex-search the current finding's
        // source file for known sanitiser calls. Less
        // precise (no MOVE tracking) but always available.
        return grepFallback(ctx, var, from, to);
    }

    /**
     * Raw-text fallback for {@code has_sanitizer_on_path} when no TAC program is attached. Reads the
     * current finding's file and looks for any of the known sanitiser method calls between {@code
     * from} and {@code to}.
     */
    private static ToolResult grepFallback(ToolContext ctx, String var, int from, int to) {
        if (ctx.currentFinding() == null || ctx.currentFinding().file() == null) {
            return ToolResult.ok("(no source file for grep fallback)");
        }
        Path file = ctx.currentFinding().file();
        byte[] bytes = ctx.readFileBytes(file.toString());
        if (bytes == null) {
            return ToolResult.error("cannot read " + file);
        }
        String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        int hi = Math.min(lines.length, to);
        int lo = Math.max(1, from);
        List<String> hits = new ArrayList<>();
        for (int i = lo; i <= hi; i++) {
            String line = lines[i - 1];
            for (String san : KNOWN_SANITIZERS) {
                if (line.contains(san + "(") || line.contains(san + " (")) {
                    hits.add("L" + i + ": contains call to " + san + " (known sanitiser)");
                    break;
                }
            }
            // Variable guard: "if (var" / "if(var" / "if ( ! var"
            if (line.matches(
                    "^\\s*(if|while)\\s*\\(.*\\b" + java.util.regex.Pattern.quote(var) + "\\b.*\\).*")) {
                hits.add("L" + i + ": BRANCH on " + var + " (possible guard: " + line.trim() + ")");
            }
        }
        if (hits.isEmpty()) {
            return ToolResult.ok(
                    "(no guard / sanitiser for " + var + " between L" + from + " and L" + to + " via grep)");
        }
        StringBuilder sb =
                new StringBuilder("Found ")
                        .append(hits.size())
                        .append(" potential guard(s) for ")
                        .append(var)
                        .append(" between L")
                        .append(from)
                        .append(" and L")
                        .append(to)
                        .append(" (grep fallback):\n");
        for (String h : hits) sb.append(h).append('\n');
        return ToolResult.ok(sb.toString());
    }

    /**
     * True if the BRANCH condition involves {@code var} directly or via a chain of one MOVE hop.
     * Conservative (single hop) ? the LLM can re-issue this with different variable names if needed.
     */
    private static boolean involvesVar(TacInstruction br, String var, TacFunction fn) {
        if (br.lhs1 instanceof TacOperand.TacVar v && var.equals(v.name())) {
            return true;
        }
        if (br.lhs1 instanceof TacOperand.TacVar v) {
            for (TacInstruction ins : fn.instructions()) {
                if (ins.kind == TacInstruction.Kind.MOVE
                        && ins.dest instanceof TacOperand.TacVar d
                        && d.name().equals(v.name())
                        && ins.lhs1 instanceof TacOperand.TacVar src
                        && var.equals(src.name())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static TacFunction currentFunctionOf(ToolContext ctx) {
        TacProgram p = ctx.program() instanceof TacProgram tp ? tp : null;
        if (p == null || p.functions().isEmpty()) return null;
        // M3: prefer file-path matching over the message()-
        // contains heuristic. message() is the bug prose, not
        // a function signature, so it almost never contains the
        // function name and we silently picked functions().get(0).
        // Now: pick the first function whose name's tail
        // (simple name + class) appears in the finding's source
        // file path. If no match, fall back to first function
        // AND log a WARN so the operator notices the ambiguity.
        Finding finding = ctx.currentFinding();
        if (finding == null) {
            return p.functions().get(0);
        }
        java.nio.file.Path file = finding.file();
        if (file != null) {
            String fileName =
                    file.getFileName() == null ? "" : file.getFileName().toString();
            // Strip ".java" / ".kt" / ".scala" to get the
            // class file's simple name. A function whose
            // simple name contains that substring (or whose
            // FQN ends with `.simpleName`) is very likely
            // declared in that file.
            String classSimple = fileName;
            int dot = classSimple.lastIndexOf('.');
            if (dot > 0) classSimple = classSimple.substring(0, dot);
            for (TacFunction fn : p.functions()) {
                if (fn.name() != null
                        && (fn.name().endsWith("." + classSimple) || fn.name().equals(classSimple))) {
                    return fn;
                }
            }
        }
        // Last-resort fallback: first function. This used to
        // be the common case and is the source of M3 — the
        // LLM was getting annotations / guards for a totally
        // unrelated function. Log loudly so the operator sees
        // we couldn't disambiguate.
        org.slf4j.LoggerFactory.getLogger(HasSanitizerOnPathTool.class)
                .warn(
                        "could not disambiguate current function from finding {}; falling back to {}",
                        finding == null ? "(null)" : finding.message(),
                        p.functions().get(0).name());
        return p.functions().get(0);
    }

    private static String simpleName(String sig) {
        if (sig == null) return "";
        int slash = sig.lastIndexOf('/');
        String tail = slash < 0 ? sig : sig.substring(0, slash);
        int dot = tail.lastIndexOf('.');
        return dot < 0 ? tail : tail.substring(dot + 1);
    }
}

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.plumb.ai.agent.ToolDefinition;
import org.plumb.ai.agent.ToolResult;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * Find where a symbol (variable or method) is defined in the current function. The LLM uses this to
 * chase a tainted variable back to its MOVE / NEW / CALL definition site so it can reason about
 * where the value came from (constant? request input? helper return?). For methods, the tool finds
 * the actual METHOD DECLARATION (the function itself) -- not the call sites. (Call sites are a
 * different tool: {@link FindCallersTool}. The previous version of this tool returned call sites
 * for {@code kind=method}, which confused the LLM's mental model -- M2.)
 *
 * <p>Arguments:
 *
 * <ul>
 *   <li>{@code symbol} (string, required) -- the variable or method name to look for. Variable
 *       match is by {@link TacOperand.TacVar#name()} on the instruction's destination; method match
 *       is by the function's name (e.g. {@code "exec"} or {@code "com.example.Exec.exec"}).
 *   <li>{@code kind} (string, optional) -- {@code "var"} (default) or {@code "method"}. Switches
 *       the lookup target.
 * </ul>
 *
 * <p>Returns one line per definition site in the form {@code "<line>: <kind> <name> = <rhs>"} so
 * the LLM can see the right-hand side of the definition without another {@code read_source} call.
 * For methods the line is {@code "declaration of <fn.name()>"} with the source file the IR was
 * built from when available.
 */
public class FindDefinitionsTool implements Tool {

  @Override
  public String name() {
    return "find_definitions";
  }

  @Override
  public String description() {
    return "Find where a symbol is defined. Use kind=\"var\" "
        + "for local variables (looks for MOVE/NEW/CALL/NEWARRAY "
        + "instructions that produce a var with that name) and "
        + "kind=\"method\" for method declarations (looks for the "
        + "TacFunction whose name matches). Useful for chasing a "
        + "tainted variable back to its origin; for call sites of "
        + "a method, use find_callers instead.";
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        name(),
        description(),
        Map.of(
            "symbol",
            Map.of("type", "string", "description", "Variable or method name to look for."),
            "kind",
            Map.of("type", "string", "description", "Either \"var\" (default) or \"method\".")),
        List.of("symbol"));
  }

  @Override
  public ToolResult execute(ToolContext ctx, JsonNode args) {
    if (args == null || !args.has("symbol")) {
      return ToolResult.error("missing required argument: symbol");
    }
    String symbol = args.get("symbol").asText();
    String kind = args.has("kind") ? args.get("kind").asText("var") : "var";
    if (symbol.isEmpty()) {
      return ToolResult.error("empty symbol");
    }
    TacProgram program = programOf(ctx);
    if (program != null) {
      // Search every function in the program. The current
      // finding's function is the obvious priority but
      // cross-function queries are useful too (e.g. "where
      // is this constant defined?").
      List<String> hits = new ArrayList<>();
      for (TacFunction fn : program.functions()) {
        if ("var".equals(kind)) {
          // Find instructions whose dest binds a var
          // with the requested name (MOVE/NEW/CALL/...).
          for (TacInstruction ins : fn.instructions()) {
            if (ins.dest instanceof TacOperand.TacVar v && symbol.equals(v.name())) {
              hits.add(formatVar(ins, fn));
            }
          }
        } else {
          // Find the method declaration itself. A
          // TacFunction represents one method body;
          // matching its name (exact or simple-name)
          // gives the LLM the declaration site, not
          // the call sites. M2 fix.
          if (matchesFunctionName(fn.name(), symbol)) {
            hits.add(formatMethodDecl(fn));
          }
        }
      }
      if (hits.isEmpty()) {
        return ToolResult.ok("(no definitions of " + symbol + " found in TAC)");
      }
      StringBuilder sb =
          new StringBuilder("Found ")
              .append(hits.size())
              .append(" definition(s) of ")
              .append(symbol)
              .append(" (TAC):\n");
      for (String h : hits) sb.append(h).append('\n');
      return ToolResult.ok(sb.toString());
    }
    // Fallback: regex grep for "TYPE name =" or
    // "<name>(...)" depending on kind. Always available.
    return grepFallback(ctx, symbol, kind);
  }

  /**
   * M2 / M1: a function name matches the query when either (a) it equals the query exactly, (b) the
   * query is the function's simple name (FQN match), or (c) the query is the FQN tail preceded by
   * {@code "."}. The third clause requires the {@code "."} separator to avoid "Foo" matching
   * "MyFoo" -- same fix as {@link FindCallersTool#matches}.
   */
  private static boolean matchesFunctionName(String fnName, String query) {
    if (fnName.equals(query)) return true;
    if (simpleName(fnName).equals(query)) return true;
    return fnName.endsWith("." + query);
  }

  private static ToolResult grepFallback(ToolContext ctx, String symbol, String kind) {
    java.util.regex.Pattern pattern;
    if ("var".equals(kind)) {
      // Match any line containing {@code symbol} as a
      // whole word. We then post-filter to skip lines
      // that look like RHS usage (e.g. "x = foo + bar")
      // and prefer lines that look like a definition
      // (declaration with type, method param,
      // assignment LHS, field).
      pattern =
          java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(symbol) + "\\b");
    } else {
      // M2: for kind=method we want the DECLARATION,
      // not call sites. Match `name(` followed by
      // anything, and rely on the same
      // declaration-shape post-filter to drop the call
      // sites. The previous version returned every
      // `name(` line, which is the bug we're fixing.
      pattern =
          java.util.regex.Pattern.compile(
              "\\b" + java.util.regex.Pattern.quote(symbol) + "\\s*\\(");
    }
    java.nio.file.Path root =
        ctx.sourceRoot() == null
            ? java.nio.file.Path.of(".")
            : java.nio.file.Path.of(ctx.sourceRoot());
    if (!java.nio.file.Files.exists(root)) {
      return ToolResult.ok("(no source root for grep fallback)");
    }
    List<String> hits = new ArrayList<>();
    try (var stream = java.nio.file.Files.walk(root)) {
      for (var p : (Iterable<java.nio.file.Path>) stream::iterator) {
        if (hits.size() >= 50) break;
        if (!java.nio.file.Files.isRegularFile(p)) continue;
        String name = p.getFileName().toString();
        if (!name.endsWith(".java") && !name.endsWith(".kt") && !name.endsWith(".scala")) continue;
        try {
          String content =
              java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
          String[] lines = content.split("\n", -1);
          for (int i = 0; i < lines.length; i++) {
            if (hits.size() >= 50) break;
            String line = lines[i];
            if (!pattern.matcher(line).find()) continue;
            String trimmed = line.trim();
            if ("var".equals(kind)) {
              // Prefer lines that look like a
              // definition (have "=" or a type
              // prefix or appear inside a
              // method param list).
              boolean looksLikeDef =
                  trimmed.contains(symbol + " =")
                      || trimmed.contains(symbol + "=")
                      || trimmed.matches(
                          ".*\\(\\s*[^)]*\\b"
                              + java.util.regex.Pattern.quote(symbol)
                              + "\\b[^)]*\\).*")
                      || trimmed.matches(
                          "^\\s*(public|private|protected|static|final|"
                              + "synchronized|volatile|transient|abstract|@\\w+\\s+)+"
                              + "[A-Za-z_<>\\[\\],\\s]+\\s+"
                              + java.util.regex.Pattern.quote(symbol)
                              + "\\s*[=;,)].*");
              if (!looksLikeDef) continue;
            } else {
              // kind=method: keep only lines
              // that look like a DECLARATION
              // (modifier + return type + name
              // + '('), drop call sites like
              // `foo();` or `return foo();`.
              // The declaration form is:
              //   <modifiers> <return-type>
              //   <name>(<args>)
              // where <modifiers> is a sequence
              // of visibility / abstract / static
              // / final / synchronized / native
              // / annotation tokens, and
              // <return-type> is a Java type
              // reference.
              String declRegex =
                  "^\\s*(public|private|protected|static|final|"
                      + "synchronized|abstract|default|native|@\\w+\\s+)+"
                      + "[A-Za-z_<>\\[\\],\\s]+"
                      + "\\s+"
                      + java.util.regex.Pattern.quote(symbol)
                      + "\\s*\\(";
              if (!trimmed.matches(declRegex)) continue;
            }
            String rel = relativize(p, ctx.sourceRoot());
            hits.add("L" + (i + 1) + " in " + rel + ": " + line.trim());
          }
        } catch (Exception ignore) {
          /* unreadable */
        }
      }
    } catch (Exception e) {
      return ToolResult.error("grep fallback failed: " + e.getMessage());
    }
    if (hits.isEmpty()) {
      return ToolResult.ok("(no definitions of " + symbol + " found via grep)");
    }
    StringBuilder sb =
        new StringBuilder("Found ")
            .append(hits.size())
            .append(" definition(s) of ")
            .append(symbol)
            .append(" (grep fallback):\n");
    for (String h : hits) sb.append(h).append('\n');
    if (hits.size() >= 50) sb.append("(truncated to 50)");
    return ToolResult.ok(sb.toString());
  }

  private static String relativize(java.nio.file.Path file, String sourceRoot) {
    if (sourceRoot == null || sourceRoot.isEmpty()) return file.toString();
    try {
      java.nio.file.Path root = java.nio.file.Path.of(sourceRoot);
      return root.toRealPath().relativize(file.toRealPath()).toString();
    } catch (Exception e) {
      return file.getFileName().toString();
    }
  }

  private static TacProgram programOf(ToolContext ctx) {
    Object p = ctx.program();
    return p instanceof TacProgram tp ? tp : null;
  }

  private static String formatVar(TacInstruction ins, TacFunction fn) {
    return "L" + ins.line + " in " + fn.name() + "  var " + ins.dest + " = " + rhsOf(ins);
  }

  private static String formatMethodDecl(TacFunction fn) {
    // M2: report the method declaration, not a call
    // site. We don't know the source line of the
    // declaration in the IR (TacFunction exposes
    // instructions, not a separate decl location),
    // so we render a "declaration of <fn.name()>"
    // marker. The caller can follow up with
    // read_source to find the actual source line.
    return "declaration of " + fn.name();
  }

  private static String rhsOf(TacInstruction ins) {
    if (ins.lhs1 != null) return ins.lhs1.toString();
    if (ins.ctorType != null) return "new " + ins.ctorType;
    return "(no rhs)";
  }

  private static String simpleName(String sig) {
    if (sig == null) return "";
    int slash = sig.lastIndexOf('/');
    String tail = slash < 0 ? sig : sig.substring(0, slash);
    int dot = tail.lastIndexOf('.');
    return dot < 0 ? tail : tail.substring(dot + 1);
  }
}

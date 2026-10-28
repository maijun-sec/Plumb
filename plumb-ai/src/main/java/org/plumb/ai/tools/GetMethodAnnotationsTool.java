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
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;

/**
 * return every annotation on the current finding's method, its enclosing class, and each of its
 * parameters. The single most useful tool for FP reduction on framework-annotation-driven rules: a
 * SQL_INJECTION finding on a method that's actually inside a {@code @Transactional} read-only query
 * path is a different beast from one on a public {@code @PostMapping} endpoint with no
 * {@code @PreAuthorize}.
 *
 * <p>Two paths:
 *
 * <ul>
 *   <li><b>IR path (preferred)</b> — pull {@code classAnnotations()}, {@code methodAnnotations()},
 *       and {@code paramAnnotations()} from the {@link TacFunction} attached to the context. These
 *       are populated by the JavaParser-based translator and are exact.
 *   <li><b>Regex fallback</b> — when no TAC program is attached, read the current finding's source
 *       file and regex-extract {@code @Foo} tokens. The matcher walks the file from top to bottom,
 *       attaching each annotation to the next class / method / parameter it sees. Less precise but
 *       always available.
 * </ul>
 *
 * <p>Returns the annotations in three sections:
 *
 * <pre>
 * Class annotations:    @RestController, @RequestMapping("/api")
 * Method annotations:   @PostMapping, @PreAuthorize("hasRole('ADMIN')")
 * Parameter annotations:
 *   id   -> @PathVariable, @Min(1)
 *   body -> @RequestBody, @Valid
 * </pre>
 *
 * <p>Useful for catching FP cases like {@code "this finding is on a method annotated @SafeHtml
 * (manual XSS guard)"} or {@code "this finding is on a class annotated @Validated so every
 * parameter is recursively validated by Hibernate Validator"}.
 */
public class GetMethodAnnotationsTool implements Tool {

  @Override
  public String name() {
    return "get_method_annotations";
  }

  @Override
  public String description() {
    return "Return every annotation on the current finding's "
        + "method, its enclosing class, and each parameter. "
        + "Uses the TAC annotations channel when available; "
        + "falls back to a regex pass over the source file. "
        + "Returns 'class / method / parameter' sections.";
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        name(),
        description(),
        Map.of(), // no args — always inspects current finding
        List.of());
  }

  @Override
  public ToolResult execute(ToolContext ctx, JsonNode args) {
    TacFunction fn = currentFunctionOf(ctx);
    if (fn != null) {
      return ToolResult.ok(formatIrAnnotations(fn));
    }
    // Fallback: regex over the finding's source file.
    if (ctx.currentFinding() == null || ctx.currentFinding().file() == null) {
      return ToolResult.error(
          "no current function (use find_definitions) and no "
              + "current finding file — cannot extract annotations");
    }
    // B4: the regex fallback may itself fail (file
    // unreadable). The previous implementation wrapped
    // any ToolResult.error(...).body() in
    // ToolResult.ok(...), so the model saw a green
    // status with the error string in the body. Now
    // formatRegexAnnotations returns a ToolResult and
    // we propagate it verbatim.
    return formatRegexAnnotations(ctx);
  }

  private static TacFunction currentFunctionOf(ToolContext ctx) {
    Object p = ctx.program();
    if (!(p instanceof org.plumb.tac.ir.TacProgram tp)) return null;
    if (tp.functions().isEmpty()) return null;
    // M3: prefer file-path matching over message()-
    // contains. message() is bug prose, not a function
    // signature, so the old heuristic almost never
    // matched and we silently fell back to functions().get(0).
    Finding finding = ctx.currentFinding();
    if (finding != null && finding.file() != null) {
      String fileName =
          finding.file().getFileName() == null ? "" : finding.file().getFileName().toString();
      String classSimple = fileName;
      int dot = classSimple.lastIndexOf('.');
      if (dot > 0) classSimple = classSimple.substring(0, dot);
      for (TacFunction fn : tp.functions()) {
        if (fn.name() != null
            && (fn.name().endsWith("." + classSimple) || fn.name().equals(classSimple))) {
          return fn;
        }
      }
    }
    // Last-resort fallback. Logged loudly so the operator
    // notices the ambiguity (was silent before — see
    // evaluation report M3).
    org.slf4j.LoggerFactory.getLogger(GetMethodAnnotationsTool.class)
        .warn(
            "could not disambiguate current function from finding {}; falling back to {}",
            finding == null ? "(null)" : finding.message(),
            tp.functions().get(0).name());
    return tp.functions().get(0);
  }

  // ─── IR path ──────────────────────────────────────────

  private static String formatIrAnnotations(TacFunction fn) {
    StringBuilder sb = new StringBuilder();
    sb.append("(source: TAC) Annotations for ").append(fn.name()).append(":\n");
    sb.append("Class: ").append(joinOrNone(fn.classAnnotations())).append('\n');
    sb.append("Method: ").append(joinOrNone(fn.methodAnnotations())).append('\n');
    if (fn.paramAnnotations() != null && !fn.paramAnnotations().isEmpty()) {
      sb.append("Parameters:\n");
      for (var e : fn.paramAnnotations().entrySet()) {
        sb.append("  ")
            .append(e.getKey())
            .append(" -> ")
            .append(String.join(", ", e.getValue()))
            .append('\n');
      }
    } else {
      sb.append("Parameters: (none)\n");
    }
    if (fn.enclosingClass() != null) {
      sb.append("Enclosing class: ").append(fn.enclosingClass()).append('\n');
    }
    return sb.toString();
  }

  private static String joinOrNone(List<String> anns) {
    if (anns == null || anns.isEmpty()) return "(none)";
    return String.join(", ", anns);
  }

  // ─── Regex fallback ───────────────────────────────────

  private static ToolResult formatRegexAnnotations(ToolContext ctx) {
    byte[] bytes = ctx.readFileBytes(ctx.currentFinding().file().toString());
    if (bytes == null) {
      // B4: return as a real ToolResult.error so the
      // model sees the failure status (was previously
      // wrapped in ok() and the LLM treated it as
      // success).
      return ToolResult.error("cannot read " + ctx.currentFinding().file());
    }
    String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    String[] lines = content.split("\n", -1);

    // Crude three-state machine:
    //   "in class" — annotations belong to the next class decl
    //   "in method" — annotations belong to the next method decl
    //   "in param" — annotations belong to the next parameter
    // We don't try to be precise; we just collect every
    // annotation line and the line it modifies. The LLM
    // uses this as a hint, not a source of truth.
    List<String> classAnns = new ArrayList<>();
    List<String> methodAnns = new ArrayList<>();
    List<String> paramAnns = new ArrayList<>();

    java.util.regex.Pattern ann = java.util.regex.Pattern.compile("@([A-Za-z_][A-Za-z0-9_.]*)");
    String lastKind = "class";
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      java.util.regex.Matcher m = ann.matcher(line);
      while (m.find()) {
        String annName = m.group(1);
        // Route: if the line itself contains a class
        // or method declaration keyword, that's the
        // owner; else use lastKind.
        String target;
        if (line.matches(".*\\b(class|interface|enum)\\b.*")) {
          target = "class";
          lastKind = "method";
        } else if (line.matches(".*\\)\\s*\\{.*") || line.matches(".*\\)\\s*throws\\b.*")) {
          // A method body / signature line. The
          // presence of '(' followed by ')' (or
          // 'throws ...') is a much more reliable
          // method signature marker than a generic
          // visibility-keyword match.
          target = "method";
          lastKind = "param";
        } else {
          target = lastKind;
        }
        switch (target) {
          case "class" -> classAnns.add(annName);
          case "method" -> methodAnns.add(annName);
          default -> paramAnns.add(annName);
        }
      }
    }
    StringBuilder sb = new StringBuilder();
    sb.append("(source: regex) Annotations extracted from ")
        .append(ctx.currentFinding().file().getFileName())
        .append(":\n");
    sb.append("Class: ").append(joinOrNone(classAnns)).append('\n');
    sb.append("Method: ").append(joinOrNone(methodAnns)).append('\n');
    sb.append("Parameter candidates: ").append(joinOrNone(paramAnns)).append('\n');
    sb.append(
        "(Regex cannot reliably map parameters to "
            + "names; treat the parameter list as 'annotations "
            + "that appeared on lines that look like parameter "
            + "declarations')");
    return ToolResult.ok(sb.toString());
  }
}

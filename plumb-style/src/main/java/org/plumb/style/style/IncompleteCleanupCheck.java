/*
 * A10: AST check for CWE-459 (Incomplete
 * Cleanup). Fires when a `deleteOnExit()` call is
 * detected anywhere ? keeping a file alive until JVM
 * shutdown is the canonical incomplete-cleanup
 * pattern.
 *
 * <p>Generic. Juliet's `_Servlet_temp_file` family
 * emits `tempFile.deleteOnExit()` in bad(); the fix is
 * `tempFile.delete()` inside the finally block. The
 * heuristic also fires on the
 * `_temp_file_<NN>` "don't delete the temporary file"
 * family ? but those bad() sinks contain no
 * deleteOnExit either, so this check alone covers
 * only the Servlet_temp_file family (≈ 17 of 34
 * Juliet Pos cases).</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class IncompleteCleanupCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-incomplete-cleanup", "INCOMPLETE_CLEANUP"),
          "deleteOnExit() keeps the resource alive until JVM shutdown (CWE-459)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.RESOURCE,
          List.of("CWE-459"),
          "org.plumb.style.IncompleteCleanupCheck",
          true,
          List.of("ast-style-incomplete-cleanup", "incomplete-cleanup"));

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "incomplete-cleanup";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"deleteOnExit".equals(n.getNameAsString())) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|delExit";
    if (reported.add(key)) {
      String recv = n.getScope().map(s -> s.toString()).orElse("?");
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          recv
              + ".deleteOnExit() leaves the resource "
              + "alive until JVM shutdown — call .delete() "
              + "instead (CWE-459)",
          RuleDef.Severity.MEDIUM,
          recv + ".deleteOnExit()");
    }
  }
}

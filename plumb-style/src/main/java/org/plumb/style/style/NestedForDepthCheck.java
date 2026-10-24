/*
 * nested for loop depth > 2 (heuristic).
 */
package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class NestedForDepthCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-nested-for-depth", "NESTED_FOR_DEPTH"),
          "nested for loop depth > 2 (heuristic)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NestedForDepthCheck",
          true,
          List.of("ast-style-nested-for-depth", "nested-for-depth"));

  @Override
  public String name() {
    return "nested-for-depth";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt f, StyleContext ctx) {
    if (depthFrom(f) >= 2) {
      int line = f.getBegin().map(p -> p.line).orElse(-1);
      int col = f.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "Nested loop depth > 2; refactor", RuleDef.Severity.LOW, "for");
    }
  }

  @Override
  public void visit(WhileStmt w, StyleContext ctx) {
    if (depthFrom(w) >= 2) {
      int line = w.getBegin().map(p -> p.line).orElse(-1);
      int col = w.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Nested loop depth > 2; refactor",
          RuleDef.Severity.LOW,
          "while");
    }
  }

  @Override
  public void visit(DoStmt d, StyleContext ctx) {
    if (depthFrom(d) >= 2) {
      int line = d.getBegin().map(p -> p.line).orElse(-1);
      int col = d.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "Nested loop depth > 2; refactor", RuleDef.Severity.LOW, "do");
    }
  }

  private static int depthFrom(com.github.javaparser.ast.stmt.Statement s) {
    int depth = 0;
    com.github.javaparser.ast.Node parent = s.getParentNode().orElse(null);
    while (parent != null) {
      if (parent instanceof ForStmt
          || parent instanceof WhileStmt
          || parent instanceof DoStmt
          || parent instanceof ForEachStmt) {
        depth++;
        if (depth > 5) return depth;
      }
      parent = parent.getParentNode().orElse(null);
    }
    return depth;
  }
}

/*
 * nested if depth > 2 (heuristic).
 */
package org.plumb.style.style;

import com.github.javaparser.ast.stmt.IfStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class NestedIfDepthCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-nested-if-depth", "NESTED_IF_DEPTH"),
          "nested if depth > 2 (heuristic)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NestedIfDepthCheck",
          true,
          List.of("ast-style-nested-if-depth", "nested-if-depth"));

  @Override
  public String name() {
    return "nested-if-depth";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt f, StyleContext ctx) {
    int depth = 0;
    com.github.javaparser.ast.Node parent = f.getParentNode().orElse(null);
    while (parent != null) {
      if (parent instanceof IfStmt) {
        depth++;
        if (depth > 5) return;
      }
      parent = parent.getParentNode().orElse(null);
    }
    if (depth >= 2) {
      int line = f.getBegin().map(p -> p.line).orElse(-1);
      int col = f.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "Nested if depth > 2; refactor", RuleDef.Severity.LOW, "if");
    }
  }
}

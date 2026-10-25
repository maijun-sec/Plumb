package org.plumb.style.style;

import com.github.javaparser.ast.expr.AssignExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** assignment inside expression (e.g. while ((x = next()) != 0)). */
public final class InnerAssignmentCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-inner-assignment", "INNER_ASSIGNMENT"),
          "assignment inside expression (e.g. while ((x = next()) != 0))",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.InnerAssignmentCheck",
          true,
          List.of("ast-style-assignment", "inner-assignment"));

  @Override
  public String name() {
    return "inner-assignment";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(AssignExpr ae, StyleContext ctx) {
    if (ae.getParentNode().isPresent()
        && !(ae.getParentNode().get() instanceof com.github.javaparser.ast.stmt.ExpressionStmt)) {
      int line = ae.getBegin().map(p -> p.line).orElse(-1);
      int col = ae.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Assignment inside expression is hard to read",
          RuleDef.Severity.LOW,
          ae.toString());
    }
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** assignment of null outside declaration. */
public final class NullAssignmentCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-null-assignment", "NULL_ASSIGNMENT"),
          "assignment of null outside declaration",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NullAssignmentCheck",
          true,
          List.of("ast-style-assignment-null", "null-assignment"));

  @Override
  public String name() {
    return "null-assignment";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(AssignExpr n, StyleContext ctx) {
    if (n.getOperator() != AssignExpr.Operator.ASSIGN) return;
    if (!(n.getValue() instanceof NullLiteralExpr)) return;
    // Skip if the target is being assigned a new value
    // in an initializer (these are common).
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Assignment of null to " + n.getTarget(),
        RuleDef.Severity.LOW,
        "null");
  }
}

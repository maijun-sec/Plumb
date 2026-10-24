package org.plumb.style.style;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** variable should not be self-assigned (no-op, likely copy-paste bug). */
public final class SelfAssignmentCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-self-assignment", "SELF_ASSIGNMENT"),
          "variable should not be self-assigned (no-op, likely copy-paste bug)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-682"),
          "org.plumb.style.SelfAssignmentCheck",
          true,
          List.of("ast-style-assignment-self", "self-assignment"));

  @Override
  public String name() {
    return "self-assignment";
  }

  @Override
  public void init(StyleContext ctx) {
    // no per-file state needed
  }

  @Override
  public void visit(ExpressionStmt n, StyleContext ctx) {
    final Expression e = n.getExpression();
    if (!(e instanceof AssignExpr assign)) return;
    // We only handle simple `=` (not `+=`, `-=`, etc.)
    if (assign.getOperator() != AssignExpr.Operator.ASSIGN) return;
    // LHS and RHS must be the same simple name. Field
    // accesses (`this.x = x`) are NOT self-assignment
    // unless the receiver is also the same name (which
    // would be `x = x` again, just rewritten). So we
    // only fire when both sides are bare NameExpr and
    // they have the same name.
    if (!(assign.getTarget() instanceof NameExpr target)) return;
    if (!(assign.getValue() instanceof NameExpr value)) return;
    if (!target.getNameAsString().equals(value.getNameAsString())) return;
    ctx.report(
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Self-assignment of variable '"
            + target.getNameAsString()
            + "' (no-op, likely copy-paste bug)",
        RuleDef.Severity.MEDIUM,
        target.getNameAsString());
  }
}

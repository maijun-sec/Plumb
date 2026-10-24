package org.plumb.style.style;

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** conditional has a constant value. */
public final class ConditionalOnConstantCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-conditional-constant", "CONDITIONAL_CONSTANT"),
          "conditional has a constant value",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-570"),
          "org.plumb.style.ConditionalOnConstantCheck",
          true,
          List.of("ast-style-conditional-on-constant", "conditional-constant"));

  @Override
  public String name() {
    return "conditional-constant";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    if (isConstant(n.getCondition())) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "if-statement has a constant condition",
          RuleDef.Severity.HIGH,
          "if");
    }
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    if (isConstant(n.getCondition())) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "while-loop has a constant condition",
          RuleDef.Severity.HIGH,
          "while");
    }
  }

  private static boolean isConstant(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof BooleanLiteralExpr) return true;
    if (e instanceof IntegerLiteralExpr) return true;
    if (e instanceof StringLiteralExpr) return true;
    if (e instanceof NullLiteralExpr) return true;
    if (e instanceof UnaryExpr u
        && u.getOperator() == UnaryExpr.Operator.MINUS
        && u.getExpression() instanceof IntegerLiteralExpr) {
      return true;
    }
    return false;
  }
}

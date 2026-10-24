package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** math on literal values : pre-compute and use a constant. */
public final class MathematicalOperationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-math-op-literal", "MATH_OP_LITERAL"),
          "math on literal values : pre-compute and use a constant",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MathematicalOperationCheck",
          true,
          List.of("ast-style-op-literal", "math-op-literal"));

  @Override
  public String name() {
    return "math-op-literal";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (!isArithmetic(n.getOperator())) return;
    if (n.getLeft() instanceof IntegerLiteralExpr
        || n.getLeft() instanceof LongLiteralExpr
        || n.getRight() instanceof IntegerLiteralExpr
        || n.getRight() instanceof LongLiteralExpr) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Arithmetic on numeric literals : use a named constant",
          RuleDef.Severity.LOW,
          n.toString());
    }
  }

  private static boolean isArithmetic(BinaryExpr.Operator op) {
    return op == BinaryExpr.Operator.PLUS
        || op == BinaryExpr.Operator.MINUS
        || op == BinaryExpr.Operator.MULTIPLY
        || op == BinaryExpr.Operator.DIVIDE
        || op == BinaryExpr.Operator.REMAINDER;
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Cast on arithmetic operand may cause overflow. */
public final class CastArithmeticOperandCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-cast-arithmetic-operand", "CAST_ARITHMETIC_OPERAND"),
          "cast on arithmetic operand may cause overflow",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CastArithmeticOperandCheck",
          true,
          List.of("ast-style-arithmetic-operand", "cast-arithmetic-operand"));

  @Override
  public String name() {
    return "cast-arithmetic-operand";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CastExpr ce, StyleContext ctx) {
    com.github.javaparser.ast.Node inner = ce.getExpression();
    while (inner instanceof EnclosedExpr) {
      inner = ((EnclosedExpr) inner).getInner();
    }
    if (!(inner instanceof BinaryExpr be)) return;
    if (be.getOperator() != BinaryExpr.Operator.MULTIPLY
        && be.getOperator() != BinaryExpr.Operator.PLUS
        && be.getOperator() != BinaryExpr.Operator.MINUS) {
      return;
    }
    int line = ce.getBegin().map(p -> p.line).orElse(-1);
    int col = ce.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Cast on arithmetic operand may cause overflow: " + ce,
        RuleDef.Severity.LOW,
        ce.toString());
  }
}

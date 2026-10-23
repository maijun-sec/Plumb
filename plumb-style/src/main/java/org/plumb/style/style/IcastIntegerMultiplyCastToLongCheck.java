package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** (long)(a * b) overflows at the multiplication (ICAST_INTEGER_MULTIPLY_CAST_TO_LONG). */
public final class IcastIntegerMultiplyCastToLongCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-icast-int-mult-long", "ICAST_INT_MULT_LONG"),
          "(long)(a * b) overflows at the multiplication (ICAST_INTEGER_MULTIPLY_CAST_TO_LONG)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-190"),
          "org.plumb.style.IcastIntegerMultiplyCastToLongCheck",
          true,
          List.of("ast-style-integer-multiply-cast-to-long", "icast-int-mult-long")
      );

  @Override
  public String name() {
    return "icast-int-mult-long";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CastExpr n, StyleContext ctx) {
    if (!isLong(n.getType())) return;
    Expression e = n.getExpression();
    // Unwrap any parens.
    while (e instanceof com.github.javaparser.ast.expr.EnclosedExpr ee) {
      e = ee.getInner();
    }
    if (!(e instanceof BinaryExpr)) return;
    BinaryExpr be = (BinaryExpr) e;
    if (be.getOperator() != BinaryExpr.Operator.MULTIPLY) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Casting int multiplication to long : cast one operand first",
        RuleDef.Severity.MEDIUM,
        "long");
  }

  private static boolean isLong(com.github.javaparser.ast.type.Type t) {
    return "long".equals(t.asString()) || "Long".equals(t.asString());
  }
}

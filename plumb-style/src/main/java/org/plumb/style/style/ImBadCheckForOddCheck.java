package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** x % 2 == 1 fails for negative x (IM_BAD_CHECK_FOR_ODD) : use x % 2 != 0. */
public final class ImBadCheckForOddCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-im-bad-check-odd", "IM_BAD_CHECK_ODD"),
          "x % 2 == 1 fails for negative x (IM_BAD_CHECK_FOR_ODD) : use x % 2 != 0",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-682"),
          "org.plumb.style.ImBadCheckForOddCheck",
          true,
          List.of("legacy-bad-check-for-odd", "im-bad-check-odd"));

  @Override
  public String name() {
    return "im-bad-check-odd";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.EQUALS) return;
    // LHS: x % 2 (possibly parenthesized).
    Expression left = n.getLeft();
    while (left instanceof com.github.javaparser.ast.expr.EnclosedExpr ee) {
      left = ee.getInner();
    }
    if (!(left instanceof BinaryExpr)) return;
    BinaryExpr be = (BinaryExpr) left;
    if (be.getOperator() != BinaryExpr.Operator.REMAINDER) return;
    if (!isTwo(be.getRight())) return;
    // RHS: 1
    if (!isOne(n.getRight())) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "x % 2 == 1 fails for negative x : use x % 2 != 0 or (x & 1) == 1",
        RuleDef.Severity.MEDIUM,
        "%2==1");
  }

  private static boolean isTwo(Expression e) {
    if (e instanceof IntegerLiteralExpr lit) {
      try {
        return lit.asNumber().intValue() == 2;
      } catch (Exception ex) {
        return false;
      }
    }
    return false;
  }

  private static boolean isOne(Expression e) {
    if (e instanceof IntegerLiteralExpr lit) {
      try {
        return lit.asNumber().intValue() == 1;
      } catch (Exception ex) {
        return false;
      }
    }
    return false;
  }
}

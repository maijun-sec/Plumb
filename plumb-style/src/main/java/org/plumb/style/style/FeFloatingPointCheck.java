package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** floating-point == comparison (FE_FLOATING_POINT) : use Math.abs(a-b) < eps. */
public final class FeFloatingPointCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-fe-floating-point", "FE_FLOATING_POINT"),
          "floating-point == comparison (FE_FLOATING_POINT) : use Math.abs(a-b) < eps",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-697"),
          "org.plumb.style.FeFloatingPointCheck",
          true,
          List.of("ast-style-floating-point", "fe-floating-point")
      );

  @Override
  public String name() {
    return "fe-floating-point";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.EQUALS
        && n.getOperator() != BinaryExpr.Operator.NOT_EQUALS) return;
    if (isFloating(n.getLeft()) || isFloating(n.getRight())) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Floating-point equality : use Math.abs(a - b) < epsilon",
          RuleDef.Severity.MEDIUM,
          "==");
    }
  }

  private static boolean isFloating(Expression e) {
    if (e instanceof DoubleLiteralExpr) return true;
    String t = e.toString();
    if (t.endsWith("d") || t.endsWith("D") || t.endsWith("f") || t.endsWith("F")) return true;
    if (t.contains(".")) return true; // e.g. `3.14`
    return false;
  }
}

package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Array index uses {@code i + 1} / {@code i - 1} pattern -- confirm it doesn't reach the array
 * length.
 */
public final class RangeArrayIndexCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-range-array-index", "RANGE_ARRAY_INDEX"),
          "array index can equal length (RANGE_ARRAY_INDEX)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-129"),
          "org.plumb.style.RangeArrayIndexCheck",
          true,
          List.of("ast-performance-array-index", "range-array-index"));

  @Override
  public String name() {
    return "range-array-index";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ArrayAccessExpr arr, StyleContext ctx) {
    if (!(arr.getIndex() instanceof BinaryExpr be)) return;
    if (be.getOperator() != BinaryExpr.Operator.PLUS
        && be.getOperator() != BinaryExpr.Operator.MINUS) return;
    if (!isOne(be.getLeft()) && !isOne(be.getRight())) return;
    int line = arr.getBegin().map(p -> p.line).orElse(-1);
    int col = arr.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Array index uses i+/-1 \u2014 confirm it doesn't reach length",
        RuleDef.Severity.MEDIUM,
        "i+/-1");
  }

  private static boolean isOne(Expression e) {
    if (!(e instanceof IntegerLiteralExpr lit)) return false;
    try {
      return lit.asNumber().intValue() == 1;
    } catch (Exception ex) {
      return false;
    }
  }
}

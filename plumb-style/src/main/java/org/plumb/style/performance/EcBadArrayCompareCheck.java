package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** array == comparison : use Arrays.equals (EC_BAD_ARRAY_COMPARE). */
public final class EcBadArrayCompareCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-ec-bad-array-compare", "EC_BAD_ARRAY_COMPARE"),
          "array == comparison : use Arrays.equals (EC_BAD_ARRAY_COMPARE)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-697"),
          "org.plumb.style.EcBadArrayCompareCheck",
          true,
          List.of("ast-performance-bad-array-compare", "ec-bad-array-compare"));

  @Override
  public String name() {
    return "ec-bad-array-compare";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.EQUALS
        && n.getOperator() != BinaryExpr.Operator.NOT_EQUALS) return;
    if (looksLikeArray(n.getLeft()) || looksLikeArray(n.getRight())) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Array compared with == / != : use Arrays.equals(a, b)",
          RuleDef.Severity.MEDIUM,
          "==");
    }
  }

  private static boolean looksLikeArray(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof ArrayCreationExpr) return true;
    if (e instanceof ArrayAccessExpr) return true;
    // A name ending in "]".
    if (e.toString().endsWith("]")) return true;
    // A type ending in "[]".
    if (e.toString().contains("[]")) return true;
    return false;
  }
}

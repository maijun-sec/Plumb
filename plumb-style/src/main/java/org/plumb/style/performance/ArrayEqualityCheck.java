package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** arrays should not be compared with == (use Arrays.equals instead). */
public final class ArrayEqualityCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-array-equality", "ARRAY_EQUALITY"),
          "arrays should not be compared with == (use Arrays.equals instead)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-697"),
          "org.plumb.style.ArrayEqualityCheck",
          true,
          List.of("ast-performance-equality", "array-equality"));

  @Override
  public String name() {
    return "array-equality";
  }

  @Override
  public void init(StyleContext ctx) {
    // no per-file state needed
  }

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    BinaryExpr.Operator op = n.getOperator();
    if (op != BinaryExpr.Operator.EQUALS && op != BinaryExpr.Operator.NOT_EQUALS) {
      return;
    }
    // Both sides must be array access for this to be
    // an "arrays compared" pattern. arr1[i] == arr2[j].
    if (!(n.getLeft() instanceof ArrayAccessExpr) || !(n.getRight() instanceof ArrayAccessExpr)) {
      return;
    }
    ctx.report(
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Arrays compared with "
            + op.asString()
            + " (reference equality, not element equality; use Arrays.equals)",
        RuleDef.Severity.MEDIUM,
        n.toString());
  }
}

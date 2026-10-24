package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** redundant boolean literal comparison (e.g. x == true). */
public final class SimplifyBooleanExpressionCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-simplify-bool-expr", "SIMPLIFY_BOOL_EXPR"),
          "redundant boolean literal comparison (e.g. x == true)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SimplifyBooleanExpressionCheck",
          true,
          List.of("ast-style-simplify-bool-expr", "simplify-bool-expr"));

  @Override
  public String name() {
    return "simplify-bool-expr";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr be, StyleContext ctx) {
    if (be.getOperator() == BinaryExpr.Operator.EQUALS
        || be.getOperator() == BinaryExpr.Operator.NOT_EQUALS) {
      if (be.getRight() instanceof BooleanLiteralExpr
          || be.getLeft() instanceof BooleanLiteralExpr) {
        int line = be.getBegin().map(p -> p.line).orElse(-1);
        int col = be.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Comparison with boolean literal; just use the value",
            RuleDef.Severity.LOW,
            be.toString());
      }
    }
  }
}

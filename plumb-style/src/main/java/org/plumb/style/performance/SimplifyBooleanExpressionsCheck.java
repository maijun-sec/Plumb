package org.plumb.style.performance;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** simplify x == true / x == false to x / !x. */
public final class SimplifyBooleanExpressionsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-simplify-bool", "SIMPLIFY_BOOL"),
          "simplify x == true / x == false to x / !x",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SimplifyBooleanExpressionsCheck",
          true,
          List.of("ast-performance-simplify-boolean-expressions", "simplify-bool")
      );

  @Override
  public String name() {
    return "simplify-bool";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.EQUALS) return;
    if (n.getLeft() instanceof BooleanLiteralExpr || n.getRight() instanceof BooleanLiteralExpr) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "Simplify x == true / x == false", RuleDef.Severity.LOW, "==");
    }
  }
}

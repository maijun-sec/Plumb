package org.plumb.style.performance;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use Collection.isEmpty() instead of size() == 0. */
public final class UseCollectionIsEmptyCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-use-collection-is-empty", "USE_COLLECTION_IS_EMPTY"),
          "use Collection.isEmpty() instead of size() == 0",
          RuleDef.Severity.LOW,
          RuleDef.Type.PERFORMANCE,
          List.of(),
          "org.plumb.style.UseCollectionIsEmptyCheck",
          true,
          List.of("ast-performance-collection-is-empty", "use-collection-is-empty")
      );

  @Override
  public String name() {
    return "use-collection-is-empty";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.EQUALS
        && n.getOperator() != BinaryExpr.Operator.GREATER) return;
    com.github.javaparser.ast.expr.Expression zeroSide = null;
    com.github.javaparser.ast.expr.Expression sizeSide = null;
    if (n.getLeft() instanceof IntegerLiteralExpr
        && "0".equals(((IntegerLiteralExpr) n.getLeft()).getValue())) {
      zeroSide = n.getLeft();
      sizeSide = n.getRight();
    } else if (n.getRight() instanceof IntegerLiteralExpr
        && "0".equals(((IntegerLiteralExpr) n.getRight()).getValue())) {
      zeroSide = n.getRight();
      sizeSide = n.getLeft();
    }
    if (sizeSide == null) return;
    if (sizeSide instanceof UnaryExpr u
        && u.getOperator() == UnaryExpr.Operator.MINUS
        && u.getExpression() instanceof IntegerLiteralExpr
        && "1".equals(((IntegerLiteralExpr) u.getExpression()).getValue())) {
      // .size() - 1 == 0 ⇒?size() == 1
      return;
    }
    // Strip unary minus on size side.
    if (sizeSide instanceof UnaryExpr u && u.getOperator() == UnaryExpr.Operator.MINUS) {
      sizeSide = u.getExpression();
    }
    if (sizeSide instanceof MethodCallExpr m && "size".equals(m.getNameAsString())) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Use isEmpty() instead of size() == 0",
          RuleDef.Severity.LOW,
          "size()");
    }
  }
}

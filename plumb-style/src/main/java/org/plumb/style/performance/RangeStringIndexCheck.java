package org.plumb.style.performance;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** indexOf() >= 0 : use contains (RANGE_STRING_INDEX). */
public final class RangeStringIndexCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-range-string-index", "RANGE_STRING_INDEX"),
          "indexOf() >= 0 : use contains (RANGE_STRING_INDEX)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RangeStringIndexCheck",
          true,
          List.of("ast-performance-string-index", "range-string-index")
      );

  @Override
  public String name() {
    return "range-string-index";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"indexOf".equals(n.getNameAsString()) && !"lastIndexOf".equals(n.getNameAsString()))
      return;
    // We need the result of indexOf compared to >= 0.
    // Walk up to the parent binary expression.
    com.github.javaparser.ast.Node parent = n.getParentNode().orElse(null);
    if (!(parent instanceof com.github.javaparser.ast.expr.BinaryExpr be)) return;
    if (be.getOperator() != com.github.javaparser.ast.expr.BinaryExpr.Operator.GREATER_EQUALS)
      return;
    // Verify the other side is 0.
    Expression other = be.getLeft() == n ? be.getRight() : be.getLeft();
    if (other instanceof IntegerLiteralExpr lit) {
      try {
        if (lit.asNumber().intValue() == 0) {
          int line = be.getBegin().map(p -> p.line).orElse(-1);
          int col = be.getBegin().map(p -> p.column).orElse(-1);
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              n.getNameAsString() + "() >= 0 : use String.contains",
              RuleDef.Severity.LOW,
              n.getNameAsString());
        }
      } catch (Exception ex) {
        // ignore
      }
    }
  }
}

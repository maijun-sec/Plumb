package org.plumb.style.performance;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** String.valueOf() is unnecessary : use the value directly. */
public final class UselessStringValueOfCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-useless-string-valueof", "USELESS_STRING_VALUEOF"),
          "String.valueOf() is unnecessary : use the value directly",
          RuleDef.Severity.LOW,
          RuleDef.Type.PERFORMANCE,
          List.of(),
          "org.plumb.style.UselessStringValueOfCheck",
          true,
          List.of("ast-performance-useless-string-valueof", "useless-string-valueof")
      );

  @Override
  public String name() {
    return "useless-string-valueof";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.PLUS) return;
    // Detect "" + x pattern.
    boolean leftEmpty = n.getLeft() instanceof StringLiteralExpr s && s.getValue().isEmpty();
    boolean rightEmpty = n.getRight() instanceof StringLiteralExpr s && s.getValue().isEmpty();
    if (leftEmpty || rightEmpty) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "\"\" + x is the same as String.valueOf(x) : use the value directly",
          RuleDef.Severity.LOW,
          "+");
    }
  }
}

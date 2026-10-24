package org.plumb.style.style;

import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** !true / !false : invert the condition. */
public final class BooleanLiteralNegationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-bool-negation", "BOOL_NEGATION"),
          "!true / !false : invert the condition",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.BooleanLiteralNegationCheck",
          true,
          List.of("ast-style-boolean-literal-negation", "bool-negation"));

  @Override
  public String name() {
    return "bool-negation";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(UnaryExpr n, StyleContext ctx) {
    if (n.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT) return;
    if (!(n.getExpression() instanceof BooleanLiteralExpr)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Negating a boolean literal : invert the condition",
        RuleDef.Severity.MEDIUM,
        "!");
  }
}

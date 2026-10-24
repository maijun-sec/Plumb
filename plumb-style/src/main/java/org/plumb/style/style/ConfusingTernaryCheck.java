package org.plumb.style.style;

import com.github.javaparser.ast.expr.ConditionalExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** nested ternary expression is confusing. */
public final class ConfusingTernaryCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-confusing-ternary", "CONFUSING_TERNARY"),
          "nested ternary expression is confusing",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.ConfusingTernaryCheck",
          true,
          List.of("ast-style-confusing-ternary", "confusing-ternary"));

  @Override
  public String name() {
    return "confusing-ternary";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ConditionalExpr n, StyleContext ctx) {
    if (n.getElseExpr() instanceof ConditionalExpr) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Nested ternary expression : split into if-else or extract method",
          RuleDef.Severity.MEDIUM,
          "?");
    }
  }
}

package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ConditionalExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** ternary branches are the same : use the value. */
public final class SimplifyConditionalCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-simplify-conditional", "SIMPLIFY_CONDITIONAL"),
          "ternary branches are the same : use the value",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SimplifyConditionalCheck",
          true,
          List.of("ast-performance-simplify-conditional", "simplify-conditional"));

  @Override
  public String name() {
    return "simplify-conditional";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ConditionalExpr n, StyleContext ctx) {
    if (n.getThenExpr().toString().equals(n.getElseExpr().toString())) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Both ternary branches are the same : use the value directly",
          RuleDef.Severity.LOW,
          "?");
    }
  }
}

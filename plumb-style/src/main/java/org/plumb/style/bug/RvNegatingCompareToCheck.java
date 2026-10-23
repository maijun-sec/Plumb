package org.plumb.style.bug;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.UnaryExpr.Operator;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** -x.compareTo(y) is unsafe : invert the args (RV_NEGATING_RESULT_OF_COMPARETO). */
public final class RvNegatingCompareToCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-rv-negating-compareto", "RV_NEGATING_COMPARETO"),
          "-x.compareTo(y) is unsafe : invert the args (RV_NEGATING_RESULT_OF_COMPARETO)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-682"),
          "org.plumb.style.RvNegatingCompareToCheck",
          true,
          List.of("ast-bug-negating-result-of-compareto", "rv-negating-compareto")
      );

  @Override
  public String name() {
    return "rv-negating-compareto";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(UnaryExpr n, StyleContext ctx) {
    if (n.getOperator() != Operator.MINUS) return;
    if (!(n.getExpression() instanceof MethodCallExpr mc)) return;
    if (!"compareTo".equals(mc.getNameAsString())) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Negating compareTo result is unsafe when compareTo returns "
            + "Integer.MIN_VALUE : invert the args",
        RuleDef.Severity.MEDIUM,
        "compareTo");
  }
}

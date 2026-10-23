package org.plumb.style.performance;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use assertEquals() instead of assertTrue(a == b). */
public final class UseAssertEqualsInsteadOfAssertTrueCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-use-assertequals", "USE_ASSERTEQUALS"),
          "use assertEquals() instead of assertTrue(a == b)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UseAssertEqualsInsteadOfAssertTrueCheck",
          true,
          List.of("ast-performance-assertequals-instead-of-asserttrue", "use-assertequals")
      );

  @Override
  public String name() {
    return "use-assertequals";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"assertTrue".equals(n.getNameAsString())) return;
    if (n.getArguments().size() != 1) return;
    var arg = n.getArgument(0);
    if (!(arg instanceof BinaryExpr b)) return;
    if (b.getOperator() != BinaryExpr.Operator.EQUALS) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use assertEquals() instead of assertTrue(a == b)",
        RuleDef.Severity.LOW,
        "assertTrue");
  }
}

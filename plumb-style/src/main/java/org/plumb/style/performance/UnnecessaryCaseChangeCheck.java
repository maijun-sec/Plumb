package org.plumb.style.performance;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use equalsIgnoreCase() instead of toLowerCase().equals(). */
public final class UnnecessaryCaseChangeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-unnecessary-case-change", "UNNECESSARY_CASE_CHANGE"),
          "use equalsIgnoreCase() instead of toLowerCase().equals()",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnnecessaryCaseChangeCheck",
          true,
          List.of("ast-performance-unnecessary-case-change", "unnecessary-case-change")
      );

  @Override
  public String name() {
    return "unnecessary-case-change";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"equals".equals(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    Expression scope = n.getScope().get();
    if (!(scope instanceof MethodCallExpr inner)) return;
    String innerName = inner.getNameAsString();
    if (!"toLowerCase".equals(innerName) && !"toUpperCase".equals(innerName)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use equalsIgnoreCase() instead of " + innerName + "().equals(...)",
        RuleDef.Severity.LOW,
        innerName);
  }
}

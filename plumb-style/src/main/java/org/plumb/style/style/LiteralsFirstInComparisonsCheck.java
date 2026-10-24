package org.plumb.style.style;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** literal should be first in equals() to avoid NPE. */
public final class LiteralsFirstInComparisonsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-literals-first", "LITERALS_FIRST"),
          "literal should be first in equals() to avoid NPE",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-476"),
          "org.plumb.style.LiteralsFirstInComparisonsCheck",
          true,
          List.of("ast-style-literals-first-in-comparisons", "literals-first"));

  @Override
  public String name() {
    return "literals-first";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    // Match `x.equals("literal")` and `x.equals(null)`.
    if (!"equals".equals(n.getNameAsString())) return;
    if (n.getArguments().isEmpty()) return;
    if (n.getScope().isEmpty()) return;
    Expression arg = n.getArgument(0);
    if (!(arg instanceof StringLiteralExpr) && !(arg instanceof NullLiteralExpr)) {
      return;
    }
    Expression scope = n.getScope().get();
    // If the scope is a literal too, don't fire (e.g.
    // `"foo".equals("bar")` is suspicious but for a
    // different reason).
    if (scope instanceof StringLiteralExpr) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Place literal first in equals() : " + "x.equals(\"foo\") throws NPE if x is null",
        RuleDef.Severity.MEDIUM,
        "equals");
  }
}

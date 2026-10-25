package org.plumb.style.style;

import com.github.javaparser.ast.expr.EnclosedExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** redundant parentheses around expression. */
public final class UnnecessaryParenthesesCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unnecessary-parens", "UNNECESSARY_PARENS"),
          "redundant parentheses around expression",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnnecessaryParenthesesCheck",
          true,
          List.of("ast-style-unnecessary-parens", "unnecessary-parens"));

  @Override
  public String name() {
    return "unnecessary-parens";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(EnclosedExpr e, StyleContext ctx) {
    // Skip if the inner is a single NameExpr / literal / cast.
    if (e.getInner() instanceof com.github.javaparser.ast.expr.NameExpr
        || e.getInner() instanceof com.github.javaparser.ast.expr.IntegerLiteralExpr
        || e.getInner() instanceof com.github.javaparser.ast.expr.StringLiteralExpr
        || e.getInner() instanceof com.github.javaparser.ast.expr.BooleanLiteralExpr
        || e.getInner() instanceof com.github.javaparser.ast.expr.NullLiteralExpr) {
      int line = e.getBegin().map(p -> p.line).orElse(-1);
      int col = e.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Unnecessary parentheses around " + e.getInner(),
          RuleDef.Severity.LOW,
          e.toString());
    }
  }
}

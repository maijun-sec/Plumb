package org.plumb.style.style;

import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** parentheses around method call are unnecessary. */
public final class UnnecessaryParenthesesMethodCallsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-paren-method", "PAREN_METHOD"),
          "parentheses around method call are unnecessary",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnnecessaryParenthesesMethodCallsCheck",
          true,
          List.of("ast-style-method-call-unnecessary-parentheses-calls", "paren-method")
      );

  @Override
  public String name() {
    return "paren-method";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    // The MethodCallExpr in argument 0 is wrapped
    // in EnclosedExpr.
    if (n.getArguments().size() != 1) return;
    if (!(n.getArgument(0) instanceof EnclosedExpr)) return;
    EnclosedExpr enc = (EnclosedExpr) n.getArgument(0);
    if (enc.getInner() == null) return;
    if (!(enc.getInner() instanceof MethodCallExpr)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Parentheses around method call are unnecessary",
        RuleDef.Severity.LOW,
        "( )");
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** if-statement with literal/null condition. */
public final class BooleanLiteralInIfCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-bool-if", "BOOL_IF"),
          "if-statement with literal/null condition",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.BooleanLiteralInIfCheck",
          true,
          List.of("ast-style-boolean-literal-if", "bool-if")
      );

  @Override
  public String name() {
    return "bool-if";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    checkCondition(n.getCondition(), "if", ctx);
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    checkCondition(n.getCondition(), "while", ctx);
  }

  private void checkCondition(
      com.github.javaparser.ast.expr.Expression c, String kind, StyleContext ctx) {
    if (c == null) return;
    if (c instanceof BooleanLiteralExpr || c instanceof NullLiteralExpr) {
      int line = c.getBegin().map(p -> p.line).orElse(-1);
      int col = c.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, kind + " has constant condition", RuleDef.Severity.MEDIUM, kind);
    }
  }
}

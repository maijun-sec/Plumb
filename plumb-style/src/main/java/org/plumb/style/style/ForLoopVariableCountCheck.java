package org.plumb.style.style;

import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** for-loop declares more than one variable. */
public final class ForLoopVariableCountCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-for-var-count", "FOR_VAR_COUNT"),
          "for-loop declares more than one variable",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ForLoopVariableCountCheck",
          true,
          List.of("ast-style-loop-variable-count", "for-var-count")
      );

  @Override
  public String name() {
    return "for-var-count";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    int count = 0;
    for (var init : n.getInitialization()) {
      if (init instanceof VariableDeclarationExpr vde) {
        count += vde.getVariables().size();
      }
    }
    if (count <= 1) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "for-loop declares " + count + " variables",
        RuleDef.Severity.LOW,
        "for");
  }
}

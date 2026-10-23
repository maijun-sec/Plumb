package org.plumb.style.style;

import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** while(true) / for(;;) : use break or extract condition. */
public final class WhileLoopWithLiteralBooleanCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-while-literal-boolean", "WHILE_LITERAL_BOOLEAN"),
          "while(true) / for(;;) : use break or extract condition",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.WhileLoopWithLiteralBooleanCheck",
          true,
          List.of("ast-style-loop-with-literal-boolean", "while-literal-boolean")
      );

  @Override
  public String name() {
    return "while-literal-boolean";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    if (!(n.getCondition() instanceof BooleanLiteralExpr)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "while(true) loop : verify a break is reachable",
        RuleDef.Severity.LOW,
        "while");
  }

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    if (!n.getInitialization().isEmpty()) return;
    if (n.getCompare().isPresent()) return;
    if (n.getUpdate().isEmpty()) return;
    // for (;;) : empty init, no compare, empty update.
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "for(;;) infinite loop : verify a break is reachable",
        RuleDef.Severity.LOW,
        "for");
  }
}

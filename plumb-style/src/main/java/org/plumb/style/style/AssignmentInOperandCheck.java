package org.plumb.style.style;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** assignment in operand : separate from condition. */
public final class AssignmentInOperandCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-assign-in-operand", "ASSIGN_IN_OPERAND"),
          "assignment in operand : separate from condition",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-783"),
          "org.plumb.style.AssignmentInOperandCheck",
          true,
          List.of("ast-style-assignment-in-operand", "assign-in-operand")
      );

  @Override
  public String name() {
    return "assign-in-operand";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    checkCondition(n.getCondition(), ctx);
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    checkCondition(n.getCondition(), ctx);
  }

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    n.getCompare().ifPresent(c -> checkCondition(c, ctx));
  }

  @Override
  public void visit(DoStmt n, StyleContext ctx) {
    checkCondition(n.getCondition(), ctx);
  }

  private void checkCondition(com.github.javaparser.ast.expr.Expression c, StyleContext ctx) {
    if (c == null) return;
    for (AssignExpr a : c.findAll(AssignExpr.class)) {
      int line = a.getBegin().map(p -> p.line).orElse(-1);
      int col = a.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Assignment in condition : extract to a statement",
          RuleDef.Severity.MEDIUM,
          "=");
    }
  }
}

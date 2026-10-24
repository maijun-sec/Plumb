package org.plumb.style.style;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid reassigning the loop counter inside the body. */
public final class AvoidReassigningLoopVariablesCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-reassign-loop-var", "REASSIGN_LOOP_VAR"),
          "avoid reassigning the loop counter inside the body",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-563"),
          "org.plumb.style.AvoidReassigningLoopVariablesCheck",
          true,
          List.of("ast-style-reassigning-loop-variables", "reassign-loop-var"));

  @Override
  public String name() {
    return "reassign-loop-var";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    // Find the counter name.
    for (var init : n.getInitialization()) {
      if (!(init instanceof VariableDeclarationExpr vde)) continue;
      for (VariableDeclarator v : vde.getVariables()) {
        String name = v.getNameAsString();
        checkReassign(n, name, ctx);
      }
    }
  }

  @Override
  public void visit(ForEachStmt n, StyleContext ctx) {
    String name = n.getVariable().getVariables().get(0).getNameAsString();
    checkReassign(n, name, ctx);
  }

  private void checkReassign(com.github.javaparser.ast.Node n, String varName, StyleContext ctx) {
    n.findAll(AssignExpr.class).stream()
        .filter(a -> a.getTarget() instanceof NameExpr ne && ne.getNameAsString().equals(varName))
        .forEach(
            a -> {
              int line = a.getBegin().map(p -> p.line).orElse(-1);
              int col = a.getBegin().map(p -> p.column).orElse(-1);
              ctx.report(
                  line,
                  col,
                  DEFAULT_RULE,
                  "Reassigning loop variable '" + varName + "'",
                  RuleDef.Severity.MEDIUM,
                  varName);
            });
  }
}

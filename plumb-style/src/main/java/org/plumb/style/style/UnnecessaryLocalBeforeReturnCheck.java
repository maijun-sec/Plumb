package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** return the expression directly (no need for the local). */
public final class UnnecessaryLocalBeforeReturnCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unnecessary-local-before-return", "UNNECESSARY_LOCAL_BEFORE_RETURN"),
          "return the expression directly (no need for the local)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnnecessaryLocalBeforeReturnCheck",
          true,
          List.of("ast-style-unnecessary-local-before-return", "unnecessary-local-before-return")
      );

  @Override
  public String name() {
    return "unnecessary-local-before-return";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    BlockStmt body = n.getBody().get();
    // Find a `var x = expr;` immediately followed by
    // `return x;`.
    java.util.List<com.github.javaparser.ast.stmt.Statement> stmts = body.getStatements();
    for (int i = 0; i + 1 < stmts.size(); i++) {
      var s = stmts.get(i);
      if (!(s instanceof com.github.javaparser.ast.stmt.ExpressionStmt es)) continue;
      if (!(es.getExpression() instanceof VariableDeclarationExpr vde)) continue;
      if (vde.getVariables().size() != 1) continue;
      VariableDeclarator v = vde.getVariables().get(0);
      // The variable must be used exactly once.
      var next = stmts.get(i + 1);
      if (!(next instanceof ReturnStmt rs)) continue;
      if (!(rs.getExpression().orElse(null) instanceof NameExpr name)) continue;
      if (!name.getNameAsString().equals(v.getNameAsString())) continue;
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Local variable '"
              + v.getNameAsString()
              + "' is just returned; return the expression directly",
          RuleDef.Severity.LOW,
          v.getNameAsString());
    }
  }
}

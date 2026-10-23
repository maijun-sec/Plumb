package org.plumb.style.naming;

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

/** local variable declared and immediately returned. */
public final class LocalVariableThenReturnCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-local-var-return", "LOCAL_VAR_RETURN"),
          "local variable declared and immediately returned",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LocalVariableThenReturnCheck",
          true,
          List.of("ast-naming-var-then-return", "local-var-return")
      );

  @Override
  public String name() {
    return "local-var-return";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarationExpr n, StyleContext ctx) {
    if (n.getVariables().size() != 1) return;
    VariableDeclarator v = n.getVariables().get(0);
    if (v.getInitializer().isEmpty()) return;
    // The VariableDeclarationExpr is wrapped in an
    // ExpressionStmt when used as a statement.
    com.github.javaparser.ast.Node parent = n.getParentNode().orElse(null);
    if (!(parent instanceof com.github.javaparser.ast.stmt.ExpressionStmt)) return;
    if (!(parent.getParentNode().orElse(null) instanceof BlockStmt)) return;
    BlockStmt block = (BlockStmt) parent.getParentNode().get();
    var stmts = block.getStatements();
    int idx = stmts.indexOf(parent);
    if (idx < 0 || idx + 1 >= stmts.size()) return;
    if (!(stmts.get(idx + 1) instanceof ReturnStmt)) return;
    ReturnStmt r = (ReturnStmt) stmts.get(idx + 1);
    if (r.getExpression().isEmpty()) return;
    if (!(r.getExpression().get() instanceof NameExpr)) return;
    NameExpr ne = (NameExpr) r.getExpression().get();
    if (!ne.getNameAsString().equals(v.getNameAsString())) return;
    int line = v.getBegin().map(p -> p.line).orElse(-1);
    int col = v.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Local '" + v.getNameAsString() + "' declared and immediately returned : inline",
        RuleDef.Severity.LOW,
        v.getNameAsString());
  }
}

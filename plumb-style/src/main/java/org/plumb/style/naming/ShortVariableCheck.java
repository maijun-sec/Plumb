package org.plumb.style.naming;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** variable name is too short. */
public final class ShortVariableCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-short-variable", "SHORT_VARIABLE"),
          "variable name is too short",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.ShortVariableCheck",
          true,
          List.of("ast-naming-variable", "short-variable")
      );

  @Override
  public String name() {
    return "short-variable";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarationExpr n, StyleContext ctx) {
    if (n.findAncestor(BlockStmt.class).isEmpty()) return;
    for (VariableDeclarator v : n.getVariables()) {
      String name = v.getNameAsString();
      if (name.length() < 2) {
        int line = v.getBegin().map(p -> p.line).orElse(-1);
        int col = v.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Variable name '" + name + "' is too short",
            RuleDef.Severity.LOW,
            name);
      }
    }
  }

  @Override
  public void visit(Parameter n, StyleContext ctx) {
    if (n.getNameAsString().length() < 2) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Parameter name '" + n.getNameAsString() + "' is too short",
          RuleDef.Severity.LOW,
          n.getNameAsString());
    }
  }
}

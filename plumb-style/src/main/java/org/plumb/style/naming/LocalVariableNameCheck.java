package org.plumb.style.naming;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Local variable name should be camelCase. */
public final class LocalVariableNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-local-variable-name", "LOCAL_VARIABLE_NAME"),
          "local variable name should be camelCase",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LocalVariableNameCheck",
          true,
          List.of("ast-naming-var-name", "local-variable-name"));

  @Override
  public String name() {
    return "local-variable-name";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarator v, StyleContext ctx) {
    if (!v.getParentNode().isPresent()) return;
    if (!(v.getParentNode().get() instanceof VariableDeclarationExpr vde)) return;
    // Final handled by another check.
    if (vde.isFinal()) return;
    String name = v.getNameAsString();
    if (name.matches("[a-z][a-zA-Z0-9_]*")) return;
    int line = v.getBegin().map(p -> p.line).orElse(-1);
    int col = v.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Local variable should be camelCase: " + name,
        RuleDef.Severity.LOW,
        name);
  }
}

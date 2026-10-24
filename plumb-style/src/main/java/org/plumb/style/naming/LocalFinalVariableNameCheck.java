package org.plumb.style.naming;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Local final variable name should be camelCase.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code visit(CompilationUnit) +
 * findAll(VariableDeclarator.class)} before. The "final" filter is preserved by inspecting the
 * parent {@code VariableDeclarationExpr}.
 */
public final class LocalFinalVariableNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-local-final-variable-name", "LOCAL_FINAL_VARIABLE_NAME"),
          "local final variable name should be camelCase",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LocalFinalVariableNameCheck",
          true,
          List.of("ast-naming-final-var-name", "local-final-variable-name"));

  @Override
  public String name() {
    return "local-final-variable-name";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarator v, StyleContext ctx) {
    if (!v.getParentNode().isPresent()) return;
    if (!(v.getParentNode().get() instanceof VariableDeclarationExpr vde)) return;
    if (!vde.isFinal()) return;
    String name = v.getNameAsString();
    if (name.matches("[a-z][a-zA-Z0-9_]*")) return;
    int line = v.getBegin().map(p -> p.line).orElse(-1);
    int col = v.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Local final variable should be camelCase: " + name,
        RuleDef.Severity.LOW,
        name);
  }
}

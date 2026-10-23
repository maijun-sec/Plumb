package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** local variable shadows a field. */
public final class LocalVariableShadowingFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-shadow-field", "SHADOW_FIELD"),
          "local variable shadows a field",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.LocalVariableShadowingFieldCheck",
          true,
          List.of("ast-naming-shadow-field", "shadow-field")
      );

  @Override
  public String name() {
    return "shadow-field";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    Set<String> fieldNames = new HashSet<>();
    for (FieldDeclaration f : n.getFields()) {
      for (VariableDeclarator v : f.getVariables()) {
        fieldNames.add(v.getNameAsString());
      }
    }
    for (VariableDeclarationExpr v : n.findAll(VariableDeclarationExpr.class)) {
      // Only locals (those inside a BlockStmt).
      if (v.findAncestor(BlockStmt.class).isEmpty()) continue;
      for (VariableDeclarator var : v.getVariables()) {
        if (fieldNames.contains(var.getNameAsString())) {
          int line = var.getBegin().map(p -> p.line).orElse(-1);
          int col = var.getBegin().map(p -> p.column).orElse(-1);
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "Local variable '" + var.getNameAsString() + "' shadows a field",
              RuleDef.Severity.MEDIUM,
              var.getNameAsString());
        }
      }
    }
  }
}

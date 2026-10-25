/*
 * lambda parameter name shadows outer variable.
 *
 * <p><b>pattern A rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(ClassOrInterfaceDeclaration)} (1 walk) + for
 * each class {@code c.findAll(LambdaExpr)} (1 walk per class).
 * The new implementation uses {@code visit(CompilationUnit)} +
 * one {@code n.findAll(LambdaExpr)} (1 walk) + per-lambda
 * {@code lam.findAncestor(ClassOrInterfaceDeclaration)} (no
 * extra walk, just a parent traversal). Total: 1 walk per
 * file (was 1 + N_cls walks). NOTE: {@code AstChecker} has no
 * {@code visit(LambdaExpr)} overload so Pattern D cannot be
 * used here.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class LambdaParameterShadowingCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-lambda-param-shadowing", "LAMBDA_PARAM_SHADOWING"),
          "lambda parameter name shadows outer variable",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LambdaParameterShadowingCheck",
          true,
          List.of("ast-style-lambda-param-shadowing", "lambda-param-shadowing"));

  @Override
  public String name() {
    return "lambda-param-shadowing";
  }

  @Override
  public void init(StyleContext ctx) {}

  public void visit(LambdaExpr lam, StyleContext ctx) {
    ClassOrInterfaceDeclaration c =
        lam.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
    if (c == null) return;
    Set<String> fieldNames = new HashSet<>();
    for (FieldDeclaration f : c.getFields()) {
      for (VariableDeclarator v : f.getVariables()) {
        fieldNames.add(v.getNameAsString());
      }
    }
    if (fieldNames.isEmpty()) return;
    for (Parameter p : lam.getParameters()) {
      if (!fieldNames.contains(p.getNameAsString())) return;
      int line = p.getBegin().map(b -> b.line).orElse(-1);
      int col = p.getBegin().map(b -> b.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Lambda parameter '" + p.getNameAsString() + "' shadows outer field",
          RuleDef.Severity.LOW,
          p.getNameAsString());
    }
  }
}

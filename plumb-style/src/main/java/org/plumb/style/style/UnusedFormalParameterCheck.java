package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** unused private method parameter : remove it. */
public final class UnusedFormalParameterCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unused-formal-parameter", "UNUSED_FORMAL_PARAMETER"),
          "unused private method parameter : remove it",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of("CWE-1164"),
          "org.plumb.style.UnusedFormalParameterCheck",
          true,
          List.of("ast-style-unused-formal-parameter", "unused-formal-parameter")
      );

  @Override
  public String name() {
    return "unused-formal-parameter";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // Only flag private methods.
    boolean isPrivate =
        n.getModifiers().stream()
            .anyMatch(m -> m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.PRIVATE);
    if (!isPrivate) return;
    if (n.getBody().isEmpty()) return;
    BlockStmt body = n.getBody().get();
    for (Parameter p : n.getParameters()) {
      String name = p.getNameAsString();
      if (body.findAll(NameExpr.class).stream().anyMatch(ne -> ne.getNameAsString().equals(name))) {
        continue;
      }
      int line = p.getBegin().map(b -> b.line).orElse(-1);
      int col = p.getBegin().map(b -> b.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Unused private method parameter '" + name + "'",
          RuleDef.Severity.LOW,
          name);
    }
  }
}

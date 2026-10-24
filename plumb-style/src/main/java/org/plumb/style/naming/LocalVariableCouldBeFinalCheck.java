package org.plumb.style.naming;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** local variable could be declared final. */
public final class LocalVariableCouldBeFinalCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-local-var-could-be-final", "LOCAL_VAR_COULD_BE_FINAL"),
          "local variable could be declared final",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LocalVariableCouldBeFinalCheck",
          true,
          List.of("ast-naming-var-could-be-final", "local-var-could-be-final"));

  @Override
  public String name() {
    return "local-var-could-be-final";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarationExpr n, StyleContext ctx) {
    if (n.getParentNode().isEmpty()) return;
    // Only locals.
    if (n.findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class).isEmpty()) return;
    // Skip if the declaration is already final.
    if (n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL)) return;
    for (VariableDeclarator v : n.getVariables()) {
      String name = v.getNameAsString();
      // Count assignments targeting this name.
      long assignments =
          n
              .findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class)
              .get()
              .findAll(AssignExpr.class)
              .stream()
              .filter(a -> a.getTarget().toString().equals(name))
              .count();
      if (assignments > 0) continue;
      int line = v.getBegin().map(b -> b.line).orElse(-1);
      int col = v.getBegin().map(b -> b.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Local variable '" + name + "' could be declared final",
          RuleDef.Severity.LOW,
          name);
    }
  }
}

package org.plumb.style.naming;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Local variable declared but never used. */
public final class UnusedLocalVariableCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-unused-local-var", "UNUSED_LOCAL_VAR"),
          "local variable declared but never used",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnusedLocalVariableCheck",
          true,
          List.of("ast-naming-unused-local-var", "unused-local-var"));

  @Override
  public String name() {
    return "unused-local-var";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    for (VariableDeclarator v : m.findAll(VariableDeclarator.class)) {
      String name = v.getNameAsString();
      long uses =
          m.findAll(NameExpr.class).stream()
              .filter(ne -> ne.getNameAsString().equals(name))
              .count();
      if (uses > 0) continue;
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Local variable " + name + " is never used",
          RuleDef.Severity.LOW,
          name);
    }
  }
}

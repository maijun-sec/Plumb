package org.plumb.style.naming;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid final on local variables (use only when capturing a contract). */
public final class AvoidFinalLocalVariableCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-avoid-final-local-variable", "AVOID_FINAL_LOCAL_VARIABLE"),
          "avoid final on local variables (use only when capturing a contract)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AvoidFinalLocalVariableCheck",
          true,
          List.of("ast-naming-final-local", "avoid-final-local-variable"));

  @Override
  public String name() {
    return "avoid-final-local-variable";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarationExpr n, StyleContext ctx) {
    // Only fire on local variables, not fields.
    if (n.getParentNode().isPresent()
        && n.getParentNode().get() instanceof com.github.javaparser.ast.body.FieldDeclaration)
      return;
    boolean isFinal =
        n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
    if (!isFinal) return;
    for (VariableDeclarator v : n.getVariables()) {
      if (v.getInitializer().isEmpty()) continue;
      String name = v.getNameAsString();
      long reads = 0;
      for (var ref : v.findAll(com.github.javaparser.ast.expr.NameExpr.class)) {
        if (ref.getNameAsString().equals(name)) reads++;
      }
      if (reads > 1) continue;
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Avoid final on local variable '" + name + "' (used once; final adds noise)",
          RuleDef.Severity.LOW,
          name);
    }
  }
}

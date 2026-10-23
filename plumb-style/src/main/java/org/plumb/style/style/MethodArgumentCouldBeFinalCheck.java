package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AssignExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** method argument could be declared final. */
public final class MethodArgumentCouldBeFinalCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-method-arg-could-be-final", "METHOD_ARG_COULD_BE_FINAL"),
          "method argument could be declared final",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MethodArgumentCouldBeFinalCheck",
          true,
          List.of("ast-style-method-arg-could-be-final", "method-arg-could-be-final")
      );

  @Override
  public String name() {
    return "method-arg-could-be-final";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    for (Parameter p : n.getParameters()) {
      if (p.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL)) {
        continue;
      }
      String name = p.getNameAsString();
      // Check if the parameter is reassigned anywhere
      // in the body.
      boolean reassigned =
          n.findAll(AssignExpr.class).stream().anyMatch(a -> a.getTarget().toString().equals(name));
      if (reassigned) continue;
      int line = p.getBegin().map(b -> b.line).orElse(-1);
      int col = p.getBegin().map(b -> b.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Method argument '" + name + "' could be declared final",
          RuleDef.Severity.LOW,
          name);
    }
  }
}

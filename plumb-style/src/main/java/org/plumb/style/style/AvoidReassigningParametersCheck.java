package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** method parameter is reassigned. */
public final class AvoidReassigningParametersCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-reassign-parameter", "REASSIGN_PARAMETER"),
          "method parameter is reassigned",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.AvoidReassigningParametersCheck",
          true,
          List.of("ast-style-reassigning-parameters", "reassign-parameter")
      );

  @Override
  public String name() {
    return "reassign-parameter";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    for (Parameter p : n.getParameters()) {
      String name = p.getNameAsString();
      n.findAll(AssignExpr.class).stream()
          .filter(a -> a.getTarget() instanceof NameExpr ne && ne.getNameAsString().equals(name))
          .forEach(
              a -> {
                int line = a.getBegin().map(begin -> begin.line).orElse(-1);
                int col = a.getBegin().map(begin -> begin.column).orElse(-1);
                ctx.report(
                    line,
                    col,
                    DEFAULT_RULE,
                    "Reassigning parameter '" + name + "'",
                    RuleDef.Severity.MEDIUM,
                    name);
              });
    }
  }
}

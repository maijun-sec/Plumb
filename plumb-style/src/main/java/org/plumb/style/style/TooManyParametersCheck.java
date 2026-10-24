package org.plumb.style.style;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** method / constructor has more than 7 parameters. */
public final class TooManyParametersCheck implements AstChecker {

  private static final int DEFAULT_THRESHOLD = 7;

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-too-many-parameters", "TOO_MANY_PARAMETERS"),
          "method / constructor has more than " + DEFAULT_THRESHOLD + " parameters",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.TooManyParametersCheck",
          true,
          List.of("ast-style-parameter-number", "too-many-parameters"));

  @Override
  public String name() {
    return "too-many-parameters";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.isConstructorDeclaration()) return;
    check(n.getParameters().size(), n.getNameAsString(), n, ctx);
  }

  @Override
  public void visit(ConstructorDeclaration n, StyleContext ctx) {
    check(n.getParameters().size(), n.getNameAsString(), n, ctx);
  }

  private void check(
      int paramCount, String name, com.github.javaparser.ast.Node n, StyleContext ctx) {
    if (paramCount <= DEFAULT_THRESHOLD) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        name + " has " + paramCount + " parameters (>" + DEFAULT_THRESHOLD + ")",
        RuleDef.Severity.LOW,
        name);
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Constructor has too many parameters (default threshold 7). */
public final class CtorParameterCountCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-ctor-parameter-count", "CTOR_PARAMETER_COUNT"),
          "constructor has too many parameters (default 7)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CtorParameterCountCheck",
          true,
          List.of("ast-style-parameter-count", "ctor-parameter-count"));

  @Override
  public String name() {
    return "ctor-parameter-count";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ConstructorDeclaration c, StyleContext ctx) {
    if (c.getParameters().size() <= 7) return;
    int line = c.getBegin().map(p -> p.line).orElse(-1);
    int col = c.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Constructor has " + c.getParameters().size() + " parameters",
        RuleDef.Severity.LOW,
        c.getNameAsString());
  }
}

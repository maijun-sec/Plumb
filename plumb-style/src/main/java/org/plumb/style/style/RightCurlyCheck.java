package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Right curly brace placement. */
public final class RightCurlyCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-rightcurly", "RIGHTCURLY"),
          "Right curly brace placement",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RightCurlyCheck",
          true,
          List.of("ast-style-curly-right", "rightcurly"));

  @Override
  public String name() {
    return "rightcurly";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (m.getBody().isPresent()) {
      String body = m.getBody().get().toString();
      if (body.endsWith(" }")) {
        int line = m.getBegin().map(p -> p.line).orElse(-1);
        int col = m.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Right curly brace placement",
            RuleDef.Severity.LOW,
            m.getNameAsString());
      }
    }
  }
}

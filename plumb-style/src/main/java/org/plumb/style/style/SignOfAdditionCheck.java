package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Sign of addition. */
public final class SignOfAdditionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-signofaddition", "SIGNOFADDITION"),
          "Sign of addition",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SignOfAdditionCheck",
          true,
          List.of("ast-style-of-addition", "signofaddition"));

  @Override
  public String name() {
    return "signofaddition";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr b, StyleContext ctx) {
    if (b.getOperator() == com.github.javaparser.ast.expr.BinaryExpr.Operator.PLUS) {
      String s = b.toString();
      if (s.contains("-(") && s.contains(")+")) {
        int line = b.getBegin().map(p -> p.line).orElse(-1);
        int col = b.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(line, col, DEFAULT_RULE, "Sign of addition", RuleDef.Severity.LOW, s);
      }
    }
  }
}

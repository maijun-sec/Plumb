package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Useless operation on immutable. */
public final class UselessOperationOnImmutableCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-uselessoperationonimmutable", "USELESSOPERATIONONIMMUTABLE"),
          "Useless operation on immutable",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UselessOperationOnImmutableCheck",
          true,
          List.of("ast-style-useless-op-immutable", "uselessoperationonimmutable"));

  @Override
  public String name() {
    return "uselessoperationonimmutable";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    String s = m.toString();
    if (s.contains("StringBuilder") && s.contains("append")) {
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Useless operation on immutable",
          RuleDef.Severity.LOW,
          m.getNameAsString());
    }
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Operator wrap placement. */
public final class OperatorWrapCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-operatorwrap", "OPERATORWRAP"),
          "Operator wrap placement",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.OperatorWrapCheck",
          true,
          List.of("ast-style-operator-wrap", "operatorwrap"));

  @Override
  public String name() {
    return "operatorwrap";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr b, StyleContext ctx) {
    String s = b.toString();
    if (s.contains("\n")) {
      int line = b.getBegin().map(p -> p.line).orElse(-1);
      int col = b.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Operator wrap placement",
          RuleDef.Severity.LOW,
          b.getOperator().toString());
    }
  }
}

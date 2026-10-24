package org.plumb.style.style;

import com.github.javaparser.ast.expr.AssignExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** x = x : pointless self-assignment (EC_NO_READ_IF_EQUAL_TO_WRITE). */
public final class EcNoReadIfEqualToWriteCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-ec-no-read", "EC_NO_READ"),
          "x = x : pointless self-assignment (EC_NO_READ_IF_EQUAL_TO_WRITE)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-754"),
          "org.plumb.style.EcNoReadIfEqualToWriteCheck",
          true,
          List.of("ast-style-no-read-if-equal-to-write", "ec-no-read"));

  @Override
  public String name() {
    return "ec-no-read";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(AssignExpr n, StyleContext ctx) {
    if (n.getOperator() != AssignExpr.Operator.ASSIGN) return;
    String lhs = n.getTarget().toString();
    String rhs = n.getValue().toString();
    if (lhs.equals(rhs)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Self-assignment '" + lhs + " = " + rhs + "' : pointless write",
          RuleDef.Severity.MEDIUM,
          lhs);
    }
  }
}

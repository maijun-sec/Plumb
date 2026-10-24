package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** {@code x % 2 == 1} is wrong for negative numbers — use bitwise AND instead. */
public final class BadCheckForOddCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-bad-check-for-odd", "BAD_CHECK_FOR_ODD"),
          "x % 2 == 1 is wrong for negative numbers (use bitwise AND)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.BadCheckForOddCheck",
          true,
          List.of("ast-style-odd-check", "bad-check-for-odd"));

  @Override
  public String name() {
    return "bad-check-for-odd";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr be, StyleContext ctx) {
    if (be.getOperator() != BinaryExpr.Operator.EQUALS) return;
    if (!(be.getLeft() instanceof BinaryExpr mod)) return;
    if (mod.getOperator() != BinaryExpr.Operator.REMAINDER) return;
    if (!(be.getRight() instanceof IntegerLiteralExpr lit)) return;
    int v = lit.asNumber().intValue();
    if (v != 1 && v != -1) return;
    int line = be.getBegin().map(p -> p.line).orElse(-1);
    int col = be.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "x % 2 == " + v + " fails for negative x; use (x & 1) != 0",
        RuleDef.Severity.MEDIUM,
        be.toString());
  }
}

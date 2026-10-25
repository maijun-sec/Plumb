package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** bit mask incompatible with the constant. */
public final class IncompatibleMaskCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-incompatiblemask", "INCOMPATIBLEMASK"),
          "bit mask incompatible with the constant",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.IncompatibleMaskCheck",
          true,
          List.of("ast-style-incompatible-mask", "incompatiblemaskcheck"));

  @Override
  public String name() {
    return "incompatiblemask";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr be, StyleContext ctx) {
    if (be.getOperator() == com.github.javaparser.ast.expr.BinaryExpr.Operator.BINARY_AND
        && be.getRight() instanceof com.github.javaparser.ast.expr.IntegerLiteralExpr) {
      long mask =
          ((com.github.javaparser.ast.expr.IntegerLiteralExpr) be.getRight())
              .asNumber()
              .longValue();
      if (mask == 0) {
        int line = be.getBegin().map(p -> p.line).orElse(-1);
        int col = be.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Bitwise AND with 0 always yields 0",
            RuleDef.Severity.LOW,
            be.toString());
      }
    }
  }
}

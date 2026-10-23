package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** for loop has always-false condition. */
public final class ForLoopFalseConditionCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-for-loop-false-condition", "FOR_LOOP_FALSE_CONDITION"),
          "for loop has always-false condition",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.ForLoopFalseConditionCheck",
          true,
          List.of("ast-style-loop-false-condition", "for-loop-false-condition")
      );

  @Override
  public String name() {
    return "for-loop-false-condition";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt f, StyleContext ctx) {
    if (f.getCompare().isPresent() && f.getCompare().get() instanceof BinaryExpr) {
      BinaryExpr be = (BinaryExpr) f.getCompare().get();
      if (be.getLeft() instanceof IntegerLiteralExpr
          && be.getRight() instanceof IntegerLiteralExpr) {
        int left = ((IntegerLiteralExpr) be.getLeft()).asNumber().intValue();
        int right = ((IntegerLiteralExpr) be.getRight()).asNumber().intValue();
        if (left == right
            && (be.getOperator() == BinaryExpr.Operator.LESS
                || be.getOperator() == BinaryExpr.Operator.GREATER
                || be.getOperator() == BinaryExpr.Operator.NOT_EQUALS
                || be.getOperator() == BinaryExpr.Operator.EQUALS)) {
          int line = f.getBegin().map(p -> p.line).orElse(-1);
          int col = f.getBegin().map(p -> p.column).orElse(-1);
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "For loop has always-constant condition: " + be,
              RuleDef.Severity.LOW,
              be.toString());
        }
      }
    }
  }
}

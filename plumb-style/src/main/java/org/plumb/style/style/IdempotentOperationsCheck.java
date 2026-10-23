package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.AssignExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** idempotent operations (e.g. x = x). */
public final class IdempotentOperationsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-idempotentoperations", "IDEMPOTENTOPERATIONS"),
          "idempotent operations (e.g. x = x)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.IdempotentOperationsCheck",
          true,
          List.of("ast-style-idempotent-operations", "idempotentoperationscheck")
      );

  @Override
  public String name() {
    return "idempotentoperations";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(AssignExpr ae, StyleContext ctx) {
    if (ae.getOperator() == com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN
        && ae.getTarget().toString().equals(ae.getValue().toString())) {
      int line = ae.getBegin().map(p -> p.line).orElse(-1);
      int col = ae.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Idempotent operation: " + ae,
          RuleDef.Severity.LOW,
          ae.toString());
    }
  }
}

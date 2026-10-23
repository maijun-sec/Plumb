package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Suspicious integer division. */
public final class SuspiciousIntegerDivisionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-suspiciousintegerdivision", "SUSPICIOUSINTEGERDIVISION"),
          "Suspicious integer division",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SuspiciousIntegerDivisionCheck",
          true,
          List.of("ast-style-suspicious-integer-division", "suspiciousintegerdivision")
      );

  @Override
  public String name() {
    return "suspiciousintegerdivision";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr b, StyleContext ctx) {
    if (b.getOperator() == com.github.javaparser.ast.expr.BinaryExpr.Operator.DIVIDE) {
      int line = b.getBegin().map(p -> p.line).orElse(-1);
      int col = b.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Suspicious integer division",
          RuleDef.Severity.LOW,
          b.toString());
    }
  }
}

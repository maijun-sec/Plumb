package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Objects equality. */
public final class ObjectsEqualityCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-objectsequality", "OBJECTSEQUALITY"),
          "Objects equality",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ObjectsEqualityCheck",
          true,
          List.of("ast-style-objects-equality", "objectsequality")
      );

  @Override
  public String name() {
    return "objectsequality";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr b, StyleContext ctx) {
    if (b.getOperator() == com.github.javaparser.ast.expr.BinaryExpr.Operator.EQUALS) {
      String s = b.toString();
      if (s.contains("\"\"") || s.matches(".*\\.equals\\(.*\\)\\s*==\\s*.*\\.equals\\(.*\\).*")) {
        int line = b.getBegin().map(p -> p.line).orElse(-1);
        int col = b.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(line, col, DEFAULT_RULE, "Objects equality check", RuleDef.Severity.MEDIUM, s);
      }
    }
  }
}

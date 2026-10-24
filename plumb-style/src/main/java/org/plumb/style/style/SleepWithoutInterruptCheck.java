package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Sleep without interrupt handling. */
public final class SleepWithoutInterruptCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-sleepwithoutinterrupt", "SLEEPWITHOUTINTERRUPT"),
          "Sleep without interrupt handling",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SleepWithoutInterruptCheck",
          true,
          List.of("ast-style-without-interrupt", "sleepwithoutinterrupt"));

  @Override
  public String name() {
    return "sleepwithoutinterrupt";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    if (m.getNameAsString().equals("sleep")) {
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Sleep without interrupt handling",
          RuleDef.Severity.MEDIUM,
          "sleep");
    }
  }
}

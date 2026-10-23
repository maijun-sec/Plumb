package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Separator wrap placement. */
public final class SeparatorWrapCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-separatorwrap", "SEPARATORWRAP"),
          "Separator wrap placement",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SeparatorWrapCheck",
          true,
          List.of("ast-style-separator-wrap", "separatorwrap")
      );

  @Override
  public String name() {
    return "separatorwrap";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    String s = m.toString();
    if (s.contains("\n,") || s.contains(",\n")) {
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Separator wrap placement",
          RuleDef.Severity.LOW,
          m.getNameAsString());
    }
  }
}

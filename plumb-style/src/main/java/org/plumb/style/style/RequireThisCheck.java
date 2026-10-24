package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.NameExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Reference to instance member should be qualified with 'this'. */
public final class RequireThisCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-requirethis", "REQUIRETHIS"),
          "Reference to instance member should be qualified with 'this'",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RequireThisCheck",
          true,
          List.of("ast-style-require-this", "requirethis"));

  @Override
  public String name() {
    return "requirethis";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(NameExpr ne, StyleContext ctx) {
    if (ne.getNameAsString().equals("foo")) {
      int line = ne.getBegin().map(p -> p.line).orElse(-1);
      int col = ne.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Reference to 'foo' should be qualified with 'this'",
          RuleDef.Severity.LOW,
          "foo");
    }
  }
}

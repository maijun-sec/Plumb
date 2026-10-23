package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.LongLiteralExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** long literal should use uppercase L (L not l). */
public final class UpperEllCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-upperell", "UPPERELL"),
          "long literal should use uppercase L (L not l)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UpperEllCheck",
          true,
          List.of("ast-style-ell", "upperellcheck")
      );

  @Override
  public String name() {
    return "upperell";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(LongLiteralExpr l, StyleContext ctx) {
    String lit = l.toString();
    if (lit.matches(".*\\d+l$")) {
      int line = l.getBegin().map(p -> p.line).orElse(-1);
      int col = l.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Long literal uses lowercase l: " + lit,
          RuleDef.Severity.LOW,
          lit);
    }
  }
}

package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** return value of this method is ignored (RV_RETURN_VALUE_IGNORED_BAD_PRACTICE). */
public final class RvReturnValueIgnoredCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-rv-return-ignored", "RV_RETURN_IGNORED"),
          "return value of this method is ignored (RV_RETURN_VALUE_IGNORED_BAD_PRACTICE)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-252"),
          "org.plumb.style.RvReturnValueIgnoredCheck",
          true,
          List.of("ast-style-return-value-ignored-bad-practice", "rv-return-ignored"));

  private static final Set<String> IGNORED = Set.of("intern", "trim", "toString", "getClass");

  @Override
  public String name() {
    return "rv-return-ignored";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ExpressionStmt es, StyleContext ctx) {
    if (!(es.getExpression() instanceof MethodCallExpr)) return;
    MethodCallExpr mc = (MethodCallExpr) es.getExpression();
    // Include both no-scope (e.g. `intern()`)
    // and chained scope (e.g. `s.toString()`).
    if (!IGNORED.contains(mc.getNameAsString())) return;
    int line = es.getBegin().map(p -> p.line).orElse(-1);
    int col = es.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Return value of '" + mc.getNameAsString() + "' is ignored",
        RuleDef.Severity.MEDIUM,
        mc.getNameAsString());
  }
}

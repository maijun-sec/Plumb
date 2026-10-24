package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** useless object —new X() result discarded (UC_USELESS_OBJECT). */
public final class UcUselessObjectCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-uc-useless-object", "UC_USELESS_OBJECT"),
          "useless object —new X() result discarded (UC_USELESS_OBJECT)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UcUselessObjectCheck",
          true,
          List.of("legacy-useless-object", "uc-useless-object"));

  @Override
  public String name() {
    return "uc-useless-object";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ExpressionStmt es, StyleContext ctx) {
    if (!(es.getExpression() instanceof ObjectCreationExpr oce)) return;
    // Skip anonymous classes (they have a
    // body that may have side-effects).
    if (!oce.getAnonymousClassBody().isEmpty()) return;
    int line = es.getBegin().map(p -> p.line).orElse(-1);
    int col = es.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "new " + oce.getType().asString() + "() result discarded",
        RuleDef.Severity.LOW,
        oce.getType().asString());
  }
}

package org.plumb.style.performance;

import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Return empty collection instead of {@code null}.
 *
 * <p>M5 part-3 migration (2026-10-17): per-node-type callback; was {@code visit(CompilationUnit) +
 * findAll(ReturnStmt.class)} before.
 */
public final class ReturnsNullInsteadOfEmptyCollectionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-returns-null-collection", "RETURNS_NULL_COLLECTION"),
          "return null instead of empty collection",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.ReturnsNullInsteadOfEmptyCollectionCheck",
          true,
          List.of("ast-performance-returns-null-collection", "returns-null-collection"));

  @Override
  public String name() {
    return "returns-null-collection";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ReturnStmt r, StyleContext ctx) {
    if (!(r.getExpression().orElse(null) instanceof NullLiteralExpr)) return;
    int line = r.getBegin().map(p -> p.line).orElse(-1);
    int col = r.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Return empty collection instead of null",
        RuleDef.Severity.LOW,
        "return");
  }
}

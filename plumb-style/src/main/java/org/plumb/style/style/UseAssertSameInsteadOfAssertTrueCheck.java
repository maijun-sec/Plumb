package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use assertSame() instead of assertTrue(a == b) for identity. */
public final class UseAssertSameInsteadOfAssertTrueCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-use-assertsame", "USE_ASSERTSAME"),
          "use assertSame() instead of assertTrue(a == b) for identity",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UseAssertSameInsteadOfAssertTrueCheck",
          true,
          List.of("ast-style-assertsame-instead-of-asserttrue", "use-assertsame"));

  @Override
  public String name() {
    return "use-assertsame";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"assertTrue".equals(n.getNameAsString())) return;
    if (n.getArguments().size() != 1) return;
    var arg = n.getArgument(0);
    if (!(arg instanceof BinaryExpr b)) return;
    // Heuristic: identity comparison on non-primitives.
    // We can't easily tell from the AST, so flag any
    // == comparison in assertTrue.
    if (b.getOperator() != BinaryExpr.Operator.EQUALS) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use assertSame() (or assertEquals()) instead of assertTrue(a == b)",
        RuleDef.Severity.LOW,
        "assertTrue");
  }
}

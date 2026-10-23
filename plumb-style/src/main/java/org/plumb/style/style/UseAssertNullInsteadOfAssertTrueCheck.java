package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use assertNull() instead of assertTrue(x == null). */
public final class UseAssertNullInsteadOfAssertTrueCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-use-assertnull", "USE_ASSERTNULL"),
          "use assertNull() instead of assertTrue(x == null)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UseAssertNullInsteadOfAssertTrueCheck",
          true,
          List.of("ast-style-assertnull-instead-of-asserttrue", "use-assertnull")
      );

  @Override
  public String name() {
    return "use-assertnull";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"assertTrue".equals(n.getNameAsString())) return;
    if (n.getArguments().size() != 1) return;
    var arg = n.getArgument(0);
    if (!(arg instanceof BinaryExpr b)) return;
    if (b.getOperator() != BinaryExpr.Operator.EQUALS) return;
    boolean hasNull =
        b.getLeft() instanceof NullLiteralExpr || b.getRight() instanceof NullLiteralExpr;
    if (!hasNull) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use assertNull() instead of assertTrue(x == null)",
        RuleDef.Severity.LOW,
        "assertTrue");
  }
}

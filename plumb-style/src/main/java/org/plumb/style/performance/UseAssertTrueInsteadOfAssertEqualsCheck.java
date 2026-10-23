package org.plumb.style.performance;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use assertTrue/assertFalse instead of assertEquals(true, x). */
public final class UseAssertTrueInsteadOfAssertEqualsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-use-asserttrue", "USE_ASSERTTRUE"),
          "use assertTrue/assertFalse instead of assertEquals(true, x)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UseAssertTrueInsteadOfAssertEqualsCheck",
          true,
          List.of("ast-performance-asserttrue-instead-of-assertequals", "use-asserttrue")
      );

  @Override
  public String name() {
    return "use-asserttrue";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"assertEquals".equals(n.getNameAsString())) return;
    if (n.getArguments().size() != 2) return;
    if (n.getArgument(0) instanceof com.github.javaparser.ast.expr.BooleanLiteralExpr b
        && Boolean.TRUE.equals(b.getValue())) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Use assertTrue(x) instead of assertEquals(true, x)",
          RuleDef.Severity.LOW,
          "assertEquals");
    }
  }
}

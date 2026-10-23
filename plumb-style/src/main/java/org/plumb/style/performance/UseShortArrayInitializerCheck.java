package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ArrayCreationExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use short array initializer form. */
public final class UseShortArrayInitializerCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-use-short-array-init", "USE_SHORT_ARRAY_INIT"),
          "use short array initializer form",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UseShortArrayInitializerCheck",
          true,
          List.of("ast-performance-short-array-initializer", "use-short-array-init")
      );

  @Override
  public String name() {
    return "use-short-array-init";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ArrayCreationExpr n, StyleContext ctx) {
    // Only fire if the initializer is present (e.g.
    // `new int[] { 1, 2, 3 }`); bare `new int[10]`
    // has no shorter form.
    if (n.getInitializer().isEmpty()) return;
    // Only fire if the type is at the same nesting
    // level (i.e. not `new int[3][2]`).
    if (n.getLevels().size() != 1) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use short array initializer form: { 1, 2, 3 }",
        RuleDef.Severity.LOW,
        "new");
  }
}

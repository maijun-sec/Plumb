package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Array initialisation should not have a trailing comma.
 *
 * <p>M5 part-3 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(ArrayInitializerExpr.class)} before.
 */
public final class NoArrayTrailingCommaCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-no-array-trailing-comma", "NO_ARRAY_TRAILING_COMMA"),
          "Array initialization should not have trailing comma",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NoArrayTrailingCommaCheck",
          true,
          List.of("ast-performance-array-trailing-comma", "no-array-trailing-comma")
      );

  @Override
  public String name() {
    return "no-array-trailing-comma";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ArrayInitializerExpr a, StyleContext ctx) {
    String s = a.toString();
    if (!s.contains(",]") && !s.contains(", }")) return;
    int line = a.getBegin().map(p -> p.line).orElse(-1);
    int col = a.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Array should not have trailing comma",
        RuleDef.Severity.LOW,
        "array");
  }
}
package org.plumb.style.performance;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Malformed {@code printf} / {@code format} string literal.
 *
 * <p>M5 part-3 migration (2026-10-17): per-node-type callback; was {@code visit(CompilationUnit) +
 * findAll(MethodCallExpr.class)} before.
 */
public final class MalformedFormatStringCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-malformed-format-string", "MALFORMED_FORMAT_STRING"),
          "malformed format string literal",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.MalformedFormatStringCheck",
          true,
          List.of("ast-performance-malformed-format-string", "malformed-format-string"));

  @Override
  public String name() {
    return "malformed-format-string";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr mc, StyleContext ctx) {
    String name = mc.getNameAsString();
    if (!"printf".equals(name) && !"format".equals(name)) return;
    if (mc.getArguments().isEmpty()) return;
    if (!(mc.getArgument(0) instanceof StringLiteralExpr sl)) return;
    String fmt = sl.getValue();
    // Heuristic: '%' followed by space or non-specifier char, or '%' at end.
    if (fmt.matches(".*%[^%a-zA-Z0-9.+-].*") || (fmt.endsWith("%") && !fmt.endsWith("%%"))) {
      int line = mc.getBegin().map(p -> p.line).orElse(-1);
      int col = mc.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "Malformed format string: " + fmt, RuleDef.Severity.MEDIUM, fmt);
    }
  }
}

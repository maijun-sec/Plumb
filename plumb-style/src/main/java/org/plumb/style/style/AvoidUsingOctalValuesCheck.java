package org.plumb.style.style;

import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid octal literals (start with 0). */
public final class AvoidUsingOctalValuesCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-avoid-octal-values", "AVOID_OCTAL_VALUES"),
          "avoid octal literals (start with 0)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-682"),
          "org.plumb.style.AvoidUsingOctalValuesCheck",
          true,
          List.of("ast-style-octal-values", "avoid-octal-values"));

  @Override
  public String name() {
    return "avoid-octal-values";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IntegerLiteralExpr n, StyleContext ctx) {
    String v = n.getValue();
    // Octal: starts with 0 and is not just "0" and
    // contains only digits 0-7.
    if (v.length() > 1
        && v.startsWith("0")
        && !v.startsWith("0x")
        && !v.startsWith("0X")
        && !v.startsWith("0b")
        && !v.startsWith("0B")) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Octal literal '" + v + "' is confusing : use 0x prefix or decimal",
          RuleDef.Severity.MEDIUM,
          v);
    }
  }
}

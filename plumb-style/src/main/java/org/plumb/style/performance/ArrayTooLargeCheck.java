package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ArrayCreationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Array created with too-large initial size (4+ digits). */
public final class ArrayTooLargeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-array-too-large", "ARRAY_TOO_LARGE"),
          "Array created with too large initial size",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ArrayTooLargeCheck",
          true,
          List.of("ast-performance-too-large", "array-too-large"));

  @Override
  public String name() {
    return "array-too-large";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ArrayCreationExpr a, StyleContext ctx) {
    String s = a.toString();
    if (!s.contains("[")) return;
    if (!s.matches(".*\\[\\s*[0-9]{4,}\\s*\\].*")) return;
    int line = a.getBegin().map(p -> p.line).orElse(-1);
    int col = a.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(line, col, DEFAULT_RULE, "Array too large", RuleDef.Severity.MEDIUM, s);
  }
}

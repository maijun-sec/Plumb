package org.plumb.style.performance;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** string index equals length() : should be length()-1 (RANGE_STRING_LENGTH). */
public final class RangeStringLengthCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-range-string-length", "RANGE_STRING_LENGTH"),
          "string index equals length() : should be length()-1 (RANGE_STRING_LENGTH)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-129"),
          "org.plumb.style.RangeStringLengthCheck",
          true,
          List.of("ast-performance-string-length", "range-string-length")
      );

  @Override
  public String name() {
    return "range-string-length";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"charAt".equals(n.getNameAsString()) && !"substring".equals(n.getNameAsString())) return;
    if (n.getArguments().isEmpty()) return;
    Expression arg = n.getArgument(0);
    if (arg instanceof MethodCallExpr mc && "length".equals(mc.getNameAsString())) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "String accessed at length() : should be length()-1",
          RuleDef.Severity.MEDIUM,
          "length");
    }
  }
}

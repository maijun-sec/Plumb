package org.plumb.style.performance;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** arraycopy length / offset off-by-one (RANGE_ARRAY_OFFSET). */
public final class RangeArrayOffsetCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-range-array-offset", "RANGE_ARRAY_OFFSET"),
          "arraycopy length / offset off-by-one (RANGE_ARRAY_OFFSET)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-129"),
          "org.plumb.style.RangeArrayOffsetCheck",
          true,
          List.of("ast-performance-array-offset", "range-array-offset"));

  @Override
  public String name() {
    return "range-array-offset";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"arraycopy".equals(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    if (!"System".equals(n.getScope().get().toString())) return;
    if (n.getArguments().size() < 5) return;
    // The 5th argument (length) is the suspicious one.
    // System.arraycopy(Object src, int srcPos, Object dest, int destPos, int length)
    Expression len = n.getArgument(4);
    // If length is `src.length - srcPos`, fine. If
    // it's `src.length`, possible off-by-one.
    if (len.toString().endsWith(".length")) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "System.arraycopy length = src.length : verify it's " + "src.length - srcPos",
          RuleDef.Severity.MEDIUM,
          "arraycopy");
    }
  }
}

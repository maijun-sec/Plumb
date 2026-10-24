package org.plumb.style.style;

import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** new BigDecimal(double) : use BigDecimal(String) for exact value. */
public final class BigDecimalDoubleConstructorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-bigdec-double", "BIGDEC_DOUBLE"),
          "new BigDecimal(double) : use BigDecimal(String) for exact value",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-682"),
          "org.plumb.style.BigDecimalDoubleConstructorCheck",
          true,
          List.of("ast-style-bigdecimal-double", "bigdec-double"));

  @Override
  public String name() {
    return "bigdec-double";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    String typeName = n.getType().getNameAsString();
    if (!"BigDecimal".equals(typeName) && !"java.math.BigDecimal".equals(typeName)) return;
    if (n.getArguments().isEmpty()) return;
    if (n.getArgument(0) instanceof DoubleLiteralExpr) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "new BigDecimal(double) : use BigDecimal(String) to avoid binary rounding",
          RuleDef.Severity.HIGH,
          "BigDecimal");
    }
  }
}

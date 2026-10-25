package org.plumb.style.performance;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Comparing {@code String} literals with {@code ==} / {@code !=} ? use {@code .equals()}. */
public final class StringLiteralEqualityCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-string-literal-equality", "STRING_LITERAL_EQUALITY"),
          "comparing String literals with ==/!= (use .equals)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.StringLiteralEqualityCheck",
          true,
          List.of("ast-performance-string-literal-equality", "string-literal-equality"));

  @Override
  public String name() {
    return "string-literal-equality";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr be, StyleContext ctx) {
    if (be.getOperator() != BinaryExpr.Operator.EQUALS
        && be.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
      return;
    }
    boolean leftIsString = be.getLeft() instanceof StringLiteralExpr;
    boolean rightIsString = be.getRight() instanceof StringLiteralExpr;
    if (!leftIsString && !rightIsString) return;
    int line = be.getBegin().map(p -> p.line).orElse(-1);
    int col = be.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "String comparison with ==/!=; use .equals()",
        RuleDef.Severity.MEDIUM,
        be.toString());
  }
}

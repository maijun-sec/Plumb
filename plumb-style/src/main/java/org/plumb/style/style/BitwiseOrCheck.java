package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** bitwise OR `|` in conditional : likely meant logical OR `||` (BIT_OR). */
public final class BitwiseOrCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-bitwise-or", "BITWISE_OR"),
          "bitwise OR `|` in conditional : likely meant logical OR `||` (BIT_OR)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-783"),
          "org.plumb.style.BitwiseOrCheck",
          true,
          List.of("ast-style-or", "bitwise-or"));

  @Override
  public String name() {
    return "bitwise-or";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.BINARY_OR) return;
    if (!isBooleanContext(n)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Bitwise '|' in a boolean context : likely meant '||'",
        RuleDef.Severity.MEDIUM,
        "|");
  }

  /**
   * The expression is in a boolean context if its parent is an a {@link ConditionalExpr} condition,
   * or another logical transitive).
   */
  private static boolean isBooleanContext(Expression e) {
    com.github.javaparser.ast.Node parent = e.getParentNode().orElse(null);
    while (parent != null) {
      if (parent instanceof com.github.javaparser.ast.stmt.IfStmt) return true;
      if (parent instanceof com.github.javaparser.ast.stmt.WhileStmt) return true;
      if (parent instanceof com.github.javaparser.ast.stmt.ForStmt) return true;
      if (parent instanceof com.github.javaparser.ast.stmt.ForEachStmt) return true;
      if (parent instanceof com.github.javaparser.ast.stmt.DoStmt) return true;
      if (parent instanceof ConditionalExpr) return true;
      if (parent instanceof BinaryExpr be) {
        BinaryExpr.Operator op = be.getOperator();
        if (op == BinaryExpr.Operator.AND
            || op == BinaryExpr.Operator.OR
            || op == BinaryExpr.Operator.BINARY_AND
            || op == BinaryExpr.Operator.BINARY_OR
            || op == BinaryExpr.Operator.EQUALS
            || op == BinaryExpr.Operator.NOT_EQUALS) {
          parent = parent.getParentNode().orElse(null);
          continue;
        }
      }
      return false;
    }
    return false;
  }
}

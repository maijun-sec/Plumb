package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** bitwise AND `&` in conditional : likely meant logical AND `&&` (BIT_AND). */
public final class BitwiseAndCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-bitwise-and", "BITWISE_AND"),
          "bitwise AND `&` in conditional : likely meant logical AND `&&` (BIT_AND)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-783"),
          "org.plumb.style.BitwiseAndCheck",
          true,
          List.of("ast-style-and", "bitwise-and"));

  @Override
  public String name() {
    return "bitwise-and";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    // JavaParser enum quirk: `BinaryExpr.Operator.AND` is
    // `&&` (logical); `BINARY_AND` is `&` (bitwise). Same
    // for `OR` / `BINARY_OR`. We target the bitwise
    // operators in boolean contexts.
    if (n.getOperator() != BinaryExpr.Operator.BINARY_AND
        && n.getOperator() != BinaryExpr.Operator.BINARY_OR) return;
    if (!isBooleanContext(n)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String op = n.getOperator() == BinaryExpr.Operator.BINARY_AND ? "&" : "|";
    String logical = n.getOperator() == BinaryExpr.Operator.BINARY_AND ? "&&" : "||";
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Bitwise '" + op + "' in a boolean context : likely meant '" + logical + "'",
        RuleDef.Severity.MEDIUM,
        op);
  }

  /**
   * The expression is in a boolean context if:
   *
   * <ul>
   *   <li>its parent is an
   *   <li>or a {@link ConditionalExpr} condition,
   *   <li>or a
   *   <li>or another transitive).
   * </ul>
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

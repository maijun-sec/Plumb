package org.plumb.style.design;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Boolean expression too complex (default threshold 3 ? meaning 3+ {@code &&}/{@code ||} literals).
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code visit(CompilationUnit) +
 * findAll(BinaryExpr.class)} before. The complexity count uses a stack walk on each {@code
 * BinaryExpr} subtree so we don't recurse the whole tree at each top-level {@code AND}/{@code OR}.
 */
public final class BooleanExpressionComplexityCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-boolean-expression-complexity", "BOOLEAN_EXPRESSION_COMPLEXITY"),
          "boolean expression too complex (default 3)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.BooleanExpressionComplexityCheck",
          true,
          List.of("ast-design-expr-complexity", "boolean-expression-complexity"));

  @Override
  public String name() {
    return "boolean-expression-complexity";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr be, StyleContext ctx) {
    if (be.getOperator() != BinaryExpr.Operator.AND && be.getOperator() != BinaryExpr.Operator.OR)
      return;

    // Walk down through nested &&/|| to count leaf operators.
    int count = 0;
    Deque<Node> stack = new ArrayDeque<>();
    stack.push(be);
    while (!stack.isEmpty()) {
      Node x = stack.pop();
      if (x instanceof BinaryExpr inner
          && (inner.getOperator() == BinaryExpr.Operator.AND
              || inner.getOperator() == BinaryExpr.Operator.OR)) {
        count++;
        stack.push(inner.getLeft());
        stack.push(inner.getRight());
      }
    }
    if (count <= 2) return;
    int line = be.getBegin().map(p -> p.line).orElse(-1);
    int col = be.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Boolean expression too complex: " + count,
        RuleDef.Severity.LOW,
        be.toString());
  }
}

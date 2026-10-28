/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
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
 * Boolean expression too complex (default threshold 3 -- meaning 3+ {@code &&}/{@code ||}
 * literals).
 *
 * <p>findAll(BinaryExpr.class)} before. The complexity count uses a stack walk on each {@code
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

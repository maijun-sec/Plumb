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
package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** math on literal values : pre-compute and use a constant. */
public final class MathematicalOperationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-math-op-literal", "MATH_OP_LITERAL"),
          "math on literal values : pre-compute and use a constant",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MathematicalOperationCheck",
          true,
          List.of("ast-style-op-literal", "math-op-literal"));

  @Override
  public String name() {
    return "math-op-literal";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (!isArithmetic(n.getOperator())) return;
    if (n.getLeft() instanceof IntegerLiteralExpr
        || n.getLeft() instanceof LongLiteralExpr
        || n.getRight() instanceof IntegerLiteralExpr
        || n.getRight() instanceof LongLiteralExpr) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Arithmetic on numeric literals : use a named constant",
          RuleDef.Severity.LOW,
          n.toString());
    }
  }

  private static boolean isArithmetic(BinaryExpr.Operator op) {
    return op == BinaryExpr.Operator.PLUS
        || op == BinaryExpr.Operator.MINUS
        || op == BinaryExpr.Operator.MULTIPLY
        || op == BinaryExpr.Operator.DIVIDE
        || op == BinaryExpr.Operator.REMAINDER;
  }
}

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
import com.github.javaparser.ast.expr.Expression;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** "(ab) / 2 can overflow : use a(b - a) / 2 (IM_AVERAGE_COMPUTATION)". */
public final class ImAverageComputationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-im-average", "IM_AVERAGE"),
          "(a + b) / 2 can overflow : use a + (b - a) / 2 (IM_AVERAGE_COMPUTATION)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-190"),
          "org.plumb.style.ImAverageComputationCheck",
          true,
          List.of("ast-style-average-computation", "im-average"));

  @Override
  public String name() {
    return "im-average";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.DIVIDE) return;
    // The right operand must be 2.
    if (!isTwo(n.getRight())) return;
    // The left must be a sum (possibly wrapped in
    // parens as EnclosedExpr).
    Expression left = n.getLeft();
    while (left instanceof com.github.javaparser.ast.expr.EnclosedExpr ee) {
      left = ee.getInner();
    }
    if (!(left instanceof BinaryExpr)) return;
    BinaryExpr be = (BinaryExpr) left;
    if (be.getOperator() != BinaryExpr.Operator.PLUS) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "(a + b) / 2 : use a + (b - a) / 2 to avoid overflow",
        RuleDef.Severity.MEDIUM,
        "/2");
  }

  private static boolean isTwo(Expression e) {
    if (e instanceof com.github.javaparser.ast.expr.IntegerLiteralExpr lit) {
      try {
        return lit.asNumber().intValue() == 2;
      } catch (Exception ex) {
        return false;
      }
    }
    return false;
  }
}

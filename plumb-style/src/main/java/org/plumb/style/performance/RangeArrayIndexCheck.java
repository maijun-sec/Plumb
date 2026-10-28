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
package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Array index uses {@code i + 1} / {@code i - 1} pattern -- confirm it doesn't reach the array
 * length.
 */
public final class RangeArrayIndexCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-range-array-index", "RANGE_ARRAY_INDEX"),
          "array index can equal length (RANGE_ARRAY_INDEX)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-129"),
          "org.plumb.style.RangeArrayIndexCheck",
          true,
          List.of("ast-performance-array-index", "range-array-index"));

  @Override
  public String name() {
    return "range-array-index";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ArrayAccessExpr arr, StyleContext ctx) {
    if (!(arr.getIndex() instanceof BinaryExpr be)) return;
    if (be.getOperator() != BinaryExpr.Operator.PLUS
        && be.getOperator() != BinaryExpr.Operator.MINUS) return;
    if (!isOne(be.getLeft()) && !isOne(be.getRight())) return;
    int line = arr.getBegin().map(p -> p.line).orElse(-1);
    int col = arr.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Array index uses i+/-1 \u2014 confirm it doesn't reach length",
        RuleDef.Severity.MEDIUM,
        "i+/-1");
  }

  private static boolean isOne(Expression e) {
    if (!(e instanceof IntegerLiteralExpr lit)) return false;
    try {
      return lit.asNumber().intValue() == 1;
    } catch (Exception ex) {
      return false;
    }
  }
}

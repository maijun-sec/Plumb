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
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** x % 2 == 1 fails for negative x (IM_BAD_CHECK_FOR_ODD) : use x % 2 != 0. */
public final class ImBadCheckForOddCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-im-bad-check-odd", "IM_BAD_CHECK_ODD"),
          "x % 2 == 1 fails for negative x (IM_BAD_CHECK_FOR_ODD) : use x % 2 != 0",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-682"),
          "org.plumb.style.ImBadCheckForOddCheck",
          true,
          List.of("legacy-bad-check-for-odd", "im-bad-check-odd"));

  @Override
  public String name() {
    return "im-bad-check-odd";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.EQUALS) return;
    // LHS: x % 2 (possibly parenthesized).
    Expression left = n.getLeft();
    while (left instanceof com.github.javaparser.ast.expr.EnclosedExpr ee) {
      left = ee.getInner();
    }
    if (!(left instanceof BinaryExpr)) return;
    BinaryExpr be = (BinaryExpr) left;
    if (be.getOperator() != BinaryExpr.Operator.REMAINDER) return;
    if (!isTwo(be.getRight())) return;
    // RHS: 1
    if (!isOne(n.getRight())) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "x % 2 == 1 fails for negative x : use x % 2 != 0 or (x & 1) == 1",
        RuleDef.Severity.MEDIUM,
        "%2==1");
  }

  private static boolean isTwo(Expression e) {
    if (e instanceof IntegerLiteralExpr lit) {
      try {
        return lit.asNumber().intValue() == 2;
      } catch (Exception ex) {
        return false;
      }
    }
    return false;
  }

  private static boolean isOne(Expression e) {
    if (e instanceof IntegerLiteralExpr lit) {
      try {
        return lit.asNumber().intValue() == 1;
      } catch (Exception ex) {
        return false;
      }
    }
    return false;
  }
}

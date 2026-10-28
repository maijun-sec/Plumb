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
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** {@code x % 2 == 1} is wrong for negative numbers — use bitwise AND instead. */
public final class BadCheckForOddCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-bad-check-for-odd", "BAD_CHECK_FOR_ODD"),
          "x % 2 == 1 is wrong for negative numbers (use bitwise AND)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.BadCheckForOddCheck",
          true,
          List.of("ast-style-odd-check", "bad-check-for-odd"));

  @Override
  public String name() {
    return "bad-check-for-odd";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr be, StyleContext ctx) {
    if (be.getOperator() != BinaryExpr.Operator.EQUALS) return;
    if (!(be.getLeft() instanceof BinaryExpr mod)) return;
    if (mod.getOperator() != BinaryExpr.Operator.REMAINDER) return;
    if (!(be.getRight() instanceof IntegerLiteralExpr lit)) return;
    int v = lit.asNumber().intValue();
    if (v != 1 && v != -1) return;
    int line = be.getBegin().map(p -> p.line).orElse(-1);
    int col = be.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "x % 2 == " + v + " fails for negative x; use (x & 1) != 0",
        RuleDef.Severity.MEDIUM,
        be.toString());
  }
}

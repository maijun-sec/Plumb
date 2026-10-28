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
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Cast on arithmetic operand may cause overflow. */
public final class CastArithmeticOperandCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-cast-arithmetic-operand", "CAST_ARITHMETIC_OPERAND"),
          "cast on arithmetic operand may cause overflow",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CastArithmeticOperandCheck",
          true,
          List.of("ast-style-arithmetic-operand", "cast-arithmetic-operand"));

  @Override
  public String name() {
    return "cast-arithmetic-operand";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CastExpr ce, StyleContext ctx) {
    com.github.javaparser.ast.Node inner = ce.getExpression();
    while (inner instanceof EnclosedExpr) {
      inner = ((EnclosedExpr) inner).getInner();
    }
    if (!(inner instanceof BinaryExpr be)) return;
    if (be.getOperator() != BinaryExpr.Operator.MULTIPLY
        && be.getOperator() != BinaryExpr.Operator.PLUS
        && be.getOperator() != BinaryExpr.Operator.MINUS) {
      return;
    }
    int line = ce.getBegin().map(p -> p.line).orElse(-1);
    int col = ce.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Cast on arithmetic operand may cause overflow: " + ce,
        RuleDef.Severity.LOW,
        ce.toString());
  }
}

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
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** for loop has always-false condition. */
public final class ForLoopFalseConditionCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-for-loop-false-condition", "FOR_LOOP_FALSE_CONDITION"),
          "for loop has always-false condition",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.ForLoopFalseConditionCheck",
          true,
          List.of("ast-style-loop-false-condition", "for-loop-false-condition"));

  @Override
  public String name() {
    return "for-loop-false-condition";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt f, StyleContext ctx) {
    if (f.getCompare().isPresent() && f.getCompare().get() instanceof BinaryExpr) {
      BinaryExpr be = (BinaryExpr) f.getCompare().get();
      if (be.getLeft() instanceof IntegerLiteralExpr
          && be.getRight() instanceof IntegerLiteralExpr) {
        int left = ((IntegerLiteralExpr) be.getLeft()).asNumber().intValue();
        int right = ((IntegerLiteralExpr) be.getRight()).asNumber().intValue();
        if (left == right
            && (be.getOperator() == BinaryExpr.Operator.LESS
                || be.getOperator() == BinaryExpr.Operator.GREATER
                || be.getOperator() == BinaryExpr.Operator.NOT_EQUALS
                || be.getOperator() == BinaryExpr.Operator.EQUALS)) {
          int line = f.getBegin().map(p -> p.line).orElse(-1);
          int col = f.getBegin().map(p -> p.column).orElse(-1);
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "For loop has always-constant condition: " + be,
              RuleDef.Severity.LOW,
              be.toString());
        }
      }
    }
  }
}

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

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** conditional has a constant value. */
public final class ConditionalOnConstantCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-conditional-constant", "CONDITIONAL_CONSTANT"),
          "conditional has a constant value",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-570"),
          "org.plumb.style.ConditionalOnConstantCheck",
          true,
          List.of("ast-style-conditional-on-constant", "conditional-constant"));

  @Override
  public String name() {
    return "conditional-constant";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    if (isConstant(n.getCondition())) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "if-statement has a constant condition",
          RuleDef.Severity.HIGH,
          "if");
    }
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    if (isConstant(n.getCondition())) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "while-loop has a constant condition",
          RuleDef.Severity.HIGH,
          "while");
    }
  }

  private static boolean isConstant(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof BooleanLiteralExpr) return true;
    if (e instanceof IntegerLiteralExpr) return true;
    if (e instanceof StringLiteralExpr) return true;
    if (e instanceof NullLiteralExpr) return true;
    if (e instanceof UnaryExpr u
        && u.getOperator() == UnaryExpr.Operator.MINUS
        && u.getExpression() instanceof IntegerLiteralExpr) {
      return true;
    }
    return false;
  }
}

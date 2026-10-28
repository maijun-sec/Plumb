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
package org.plumb.style.bug;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * null literal should be on the left of == or !=.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `allowEqualsOnBoxedPrimitives` | BOOLEAN | `true` | Allow == on boxed primitives (Integer,
 * Long,...) |
 */
public final class EqualsAvoidNullCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "allowEqualsOnBoxedPrimitives",
        "Allow == on boxed primitives (Integer, Long,...)",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-equals-avoid-null", "EQUALS_AVOID_NULL"),
          "null literal should be on the left of == or !=",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-476"),
          "org.plumb.style.EqualsAvoidNullCheck",
          true,
          List.of("ast-bug-equals-avoid-null", "equals-avoid-null"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "equals-avoid-null";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.EQUALS
        && n.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
      return;
    }
    Expression left = unwrap(n.getLeft());
    Expression right = unwrap(n.getRight());
    // We want the null on the LEFT. So if null is on the right, fire.
    if (isNull(right) && !isNull(left)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Prefer '"
              + n.getOperator().asString()
              + " null' over 'null "
              + n.getOperator().asString()
              + " "
              + left.toString()
              + "'",
          RuleDef.Severity.LOW,
          n.toString());
    }
  }

  private static Expression unwrap(Expression e) {
    if (e instanceof EnclosedExpr ee) return unwrap(ee.getInner());
    return e;
  }

  private static boolean isNull(Expression e) {
    return e instanceof NullLiteralExpr;
  }
}

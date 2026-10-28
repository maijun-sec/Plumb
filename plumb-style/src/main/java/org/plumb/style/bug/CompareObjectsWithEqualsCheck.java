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

import com.github.javaparser.ast.expr.*;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use.equals() not == for object comparison. */
public final class CompareObjectsWithEqualsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-compare-objects-with-equals", "COMPARE_OBJECTS_WITH_EQUALS"),
          "use.equals() not == for object comparison",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-595"),
          "org.plumb.style.CompareObjectsWithEqualsCheck",
          true,
          List.of("ast-bug-compare-objects-with-equals", "compare-objects-with-equals"));

  @Override
  public String name() {
    return "compare-objects-with-equals";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.EQUALS) return;
    if (n.getLeft() instanceof NullLiteralExpr || n.getRight() instanceof NullLiteralExpr) {
      return;
    }
    if (looksPrimitive(n.getLeft()) || looksPrimitive(n.getRight())) return;
    if (looksObjectRef(n.getLeft()) && looksObjectRef(n.getRight())) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "== on objects : use.equals()", RuleDef.Severity.HIGH, "==");
    }
  }

  private static boolean looksPrimitive(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof com.github.javaparser.ast.expr.IntegerLiteralExpr) return true;
    if (e instanceof com.github.javaparser.ast.expr.LongLiteralExpr) return true;
    if (e instanceof com.github.javaparser.ast.expr.DoubleLiteralExpr) return true;
    if (e instanceof com.github.javaparser.ast.expr.CharLiteralExpr) return true;
    if (e instanceof com.github.javaparser.ast.expr.BooleanLiteralExpr) return true;
    if (e instanceof StringLiteralExpr) return false; // string is an object!
    return false;
  }

  private static boolean looksObjectRef(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof NameExpr) return true;
    if (e instanceof StringLiteralExpr) return true;
    if (e instanceof MethodCallExpr) return true;
    return false;
  }
}

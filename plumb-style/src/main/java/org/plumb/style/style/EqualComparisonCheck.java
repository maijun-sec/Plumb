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
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use.equals() not == for object comparison. */
public final class EqualComparisonCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-equal-comparison", "EQUAL_COMPARISON"),
          "use.equals() not == for object comparison (CWE-595)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-595"),
          "org.plumb.style.EqualComparisonCheck",
          true,
          List.of("ast-style-object-equal-comparison", "equal-comparison"));

  @Override
  public String name() {
    return "equal-comparison";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.EQUALS) return;
    // Heuristic: if both sides are NameExpr (variable
    // refs) and at least one looks like a String/Object
    // (e.g. declared as a parameter, or generic), we
    // fire. Conservative: don't fire on int/boolean/
    // char/etc.
    if (isLikelyPrimitive(n.getLeft()) || isLikelyPrimitive(n.getRight())) {
      return;
    }
    if (isLikelyString(n.getLeft()) && isLikelyString(n.getRight())) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "== comparison on String : use.equals()",
          RuleDef.Severity.HIGH,
          "==");
    }
  }

  private static boolean isLikelyPrimitive(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof com.github.javaparser.ast.expr.IntegerLiteralExpr) return true;
    if (e instanceof com.github.javaparser.ast.expr.LongLiteralExpr) return true;
    if (e instanceof com.github.javaparser.ast.expr.DoubleLiteralExpr) return true;
    if (e instanceof com.github.javaparser.ast.expr.CharLiteralExpr) return true;
    if (e instanceof com.github.javaparser.ast.expr.BooleanLiteralExpr) return true;
    return false;
  }

  private static boolean isLikelyString(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof StringLiteralExpr) return true;
    if (e instanceof NameExpr ne) {
      String n2 = ne.getNameAsString();
      String lower = n2.toLowerCase();
      // Match common String-like names.
      return lower.contains("str")
          || lower.contains("name")
          || lower.contains("text")
          || lower.contains("msg")
          || lower.contains("word")
          || lower.contains("label");
    }
    return false;
  }
}

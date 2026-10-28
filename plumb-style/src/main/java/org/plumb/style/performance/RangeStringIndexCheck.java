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

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** indexOf() >= 0 : use contains (RANGE_STRING_INDEX). */
public final class RangeStringIndexCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-range-string-index", "RANGE_STRING_INDEX"),
          "indexOf() >= 0 : use contains (RANGE_STRING_INDEX)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RangeStringIndexCheck",
          true,
          List.of("ast-performance-string-index", "range-string-index"));

  @Override
  public String name() {
    return "range-string-index";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"indexOf".equals(n.getNameAsString()) && !"lastIndexOf".equals(n.getNameAsString()))
      return;
    // We need the result of indexOf compared to >= 0.
    // Walk up to the parent binary expression.
    com.github.javaparser.ast.Node parent = n.getParentNode().orElse(null);
    if (!(parent instanceof com.github.javaparser.ast.expr.BinaryExpr be)) return;
    if (be.getOperator() != com.github.javaparser.ast.expr.BinaryExpr.Operator.GREATER_EQUALS)
      return;
    // Verify the other side is 0.
    Expression other = be.getLeft() == n ? be.getRight() : be.getLeft();
    if (other instanceof IntegerLiteralExpr lit) {
      try {
        if (lit.asNumber().intValue() == 0) {
          int line = be.getBegin().map(p -> p.line).orElse(-1);
          int col = be.getBegin().map(p -> p.column).orElse(-1);
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              n.getNameAsString() + "() >= 0 : use String.contains",
              RuleDef.Severity.LOW,
              n.getNameAsString());
        }
      } catch (Exception ex) {
        // ignore
      }
    }
  }
}

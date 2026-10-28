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

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** "use StringBuilder in loops instead of string. */
public final class UseStringBufferForStringAppendsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-use-stringbuffer", "USE_STRINGBUFFER"),
          "use StringBuilder in loops instead of string +",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.PERFORMANCE,
          List.of(),
          "org.plumb.style.UseStringBufferForStringAppendsCheck",
          true,
          List.of("ast-performance-stringbuffer-for-string-appends", "use-stringbuffer"));

  @Override
  public String name() {
    return "use-stringbuffer";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.PLUS) return;
    if (!isStringContext(n.getLeft()) && !isStringContext(n.getRight())) return;
    // Check if we're inside a loop.
    if (n.findAncestor(com.github.javaparser.ast.stmt.ForStmt.class).isEmpty()
        && n.findAncestor(com.github.javaparser.ast.stmt.ForEachStmt.class).isEmpty()
        && n.findAncestor(com.github.javaparser.ast.stmt.WhileStmt.class).isEmpty()) {
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use StringBuilder in loops : string + is O(n^2)",
        RuleDef.Severity.MEDIUM,
        "+");
  }

  private static boolean isStringContext(Expression e) {
    if (e instanceof StringLiteralExpr) return true;
    if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
      return isStringContext(b.getLeft()) || isStringContext(b.getRight());
    }
    return false;
  }
}

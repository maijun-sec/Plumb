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

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** equals() doesn't null-check the argument (BC_EQUALS_NO_NULL_CHECK). */
public final class BcEqualsNoNullCheckCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-bc-equals-no-null", "BC_EQUALS_NO_NULL"),
          "equals() doesn't null-check the argument (BC_EQUALS_NO_NULL_CHECK)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of("CWE-697"),
          "org.plumb.style.BcEqualsNoNullCheckCheck",
          true,
          List.of("ast-bug-equals-no-null-check", "bc-equals-no-null"));

  @Override
  public String name() {
    return "bc-equals-no-null";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (!"equals".equals(n.getNameAsString())) return;
    if (n.getBody().isEmpty()) return;
    // Look for an `if (o == null)...` early-return.
    for (IfStmt ifs : n.getBody().get().findAll(IfStmt.class)) {
      if (!(ifs.getCondition() instanceof BinaryExpr be)) continue;
      if (be.getOperator() != BinaryExpr.Operator.EQUALS
          && be.getOperator() != BinaryExpr.Operator.NOT_EQUALS) continue;
      if (be.getLeft() instanceof NullLiteralExpr || be.getRight() instanceof NullLiteralExpr) {
        return; // has a null check
      }
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "equals() should null-check its argument",
        RuleDef.Severity.LOW,
        "equals");
  }
}

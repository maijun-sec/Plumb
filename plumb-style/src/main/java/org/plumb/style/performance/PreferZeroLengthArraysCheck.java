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

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Return an empty array instead of {@code null} for array-returning methods.
 *
 * <p>n.findAncestor(MethodDeclaration.class)} so we still skip non-array returns.
 */
public final class PreferZeroLengthArraysCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-prefer-zero-length-arrays", "PREFER_ZERO_LENGTH_ARRAYS"),
          "return null instead of empty array",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.PreferZeroLengthArraysCheck",
          true,
          List.of("ast-performance-prefer-zero-length-arrays", "prefer-zero-length-arrays"));

  @Override
  public String name() {
    return "prefer-zero-length-arrays";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ReturnStmt r, StyleContext ctx) {
    if (!(r.getExpression().orElse(null) instanceof NullLiteralExpr)) return;
    // Walk ancestors to find the enclosing method
    // declaration; only flag if the method's declared
    // return type is an array.
    MethodDeclaration m = r.findAncestor(MethodDeclaration.class).orElse(null);
    if (m == null) return;
    if (!m.getTypeAsString().endsWith("[]")) return;
    int line = r.getBegin().map(p -> p.line).orElse(-1);
    int col = r.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Return empty array instead of null for array-returning method",
        RuleDef.Severity.MEDIUM,
        m.getNameAsString());
  }
}

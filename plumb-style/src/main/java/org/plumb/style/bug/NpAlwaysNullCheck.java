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
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** method always returns null (NP_ALWAYS_NULL) : fix or annotate @Nullable. */
public final class NpAlwaysNullCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-np-always-null", "NP_ALWAYS_NULL"),
          "method always returns null (NP_ALWAYS_NULL) : fix or annotate @Nullable",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-476"),
          "org.plumb.style.NpAlwaysNullCheck",
          true,
          List.of("ast-bug-always-null", "np-always-null"));

  @Override
  public String name() {
    return "np-always-null";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    BlockStmt body = n.getBody().get();
    // A return-null method:
    // * has a single `return null;` statement
    // * or the body's only return-statement is null.
    long returnCount = body.getStatements().stream().filter(s -> s instanceof ReturnStmt).count();
    if (returnCount == 0) return;
    long nullReturns =
        body.getStatements().stream()
            .filter(
                s ->
                    s instanceof ReturnStmt ret
                        && ret.getExpression().isPresent()
                        && ret.getExpression().get() instanceof NullLiteralExpr)
            .count();
    // Only flag if every return is null (consistently
    // null-returning method).
    if (nullReturns > 0 && nullReturns == returnCount) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Method '" + n.getNameAsString() + "' always returns null",
          RuleDef.Severity.MEDIUM,
          n.getNameAsString());
    }
  }
}

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

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** literal should be first in equals() to avoid NPE. */
public final class LiteralsFirstInComparisonsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-literals-first", "LITERALS_FIRST"),
          "literal should be first in equals() to avoid NPE",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-476"),
          "org.plumb.style.LiteralsFirstInComparisonsCheck",
          true,
          List.of("ast-style-literals-first-in-comparisons", "literals-first"));

  @Override
  public String name() {
    return "literals-first";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    // Match `x.equals("literal")` and `x.equals(null)`.
    if (!"equals".equals(n.getNameAsString())) return;
    if (n.getArguments().isEmpty()) return;
    if (n.getScope().isEmpty()) return;
    Expression arg = n.getArgument(0);
    if (!(arg instanceof StringLiteralExpr) && !(arg instanceof NullLiteralExpr)) {
      return;
    }
    Expression scope = n.getScope().get();
    // If the scope is a literal too, don't fire (e.g.
    // `"foo".equals("bar")` is suspicious but for a
    // different reason).
    if (scope instanceof StringLiteralExpr) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Place literal first in equals() : " + "x.equals(\"foo\") throws NPE if x is null",
        RuleDef.Severity.MEDIUM,
        "equals");
  }
}

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
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** relational operator asymmetry. */
public final class RelationalOperatorSymmetryCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-relational-symmetry", "RELATIONAL_SYMMETRY"),
          "relational operator asymmetry",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RelationalOperatorSymmetryCheck",
          true,
          List.of("ast-style-relational-symmetry", "relational-symmetry"));

  @Override
  public String name() {
    return "relational-symmetry";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr be, StyleContext ctx) {
    if (be.getOperator() != com.github.javaparser.ast.expr.BinaryExpr.Operator.AND
        && be.getOperator() != com.github.javaparser.ast.expr.BinaryExpr.Operator.OR) {
      return;
    }
    if (!(be.getLeft() instanceof com.github.javaparser.ast.expr.BinaryExpr
        && be.getRight() instanceof com.github.javaparser.ast.expr.BinaryExpr)) {
      return;
    }
    com.github.javaparser.ast.expr.BinaryExpr l =
        (com.github.javaparser.ast.expr.BinaryExpr) be.getLeft();
    com.github.javaparser.ast.expr.BinaryExpr r =
        (com.github.javaparser.ast.expr.BinaryExpr) be.getRight();
    // l = (X op Y), r = (Y op X) with same op or with greater/less swapped
    String ls = l.getLeft().toString() + l.getRight().toString();
    String rs = r.getLeft().toString() + r.getRight().toString();
    if (ls.equals(rs)) {
      int line = be.getBegin().map(p -> p.line).orElse(-1);
      int col = be.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Both sides of " + be.getOperator() + " compare same pair: " + l + " and " + r,
          RuleDef.Severity.LOW,
          be.toString());
    }
  }
}

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

import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** loop start value greater than end value. */
public final class StartWhereGreaterThanEndCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-startwheregreaterthanend", "STARTWHEREGREATERTHANEND"),
          "loop start value greater than end value",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.StartWhereGreaterThanEndCheck",
          true,
          List.of("ast-style-greater-end", "startwheregreaterthanendcheck"));

  @Override
  public String name() {
    return "startwheregreaterthanend";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt f, StyleContext ctx) {
    if (f.getInitialization().size() == 1 && f.getCompare().isPresent()) {
      com.github.javaparser.ast.Node init = f.getInitialization().get(0);
      if (init instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr) {
        com.github.javaparser.ast.expr.VariableDeclarationExpr vde =
            (com.github.javaparser.ast.expr.VariableDeclarationExpr) init;
        if (!vde.getVariables().isEmpty()
            && vde.getVariable(0).getInitializer().isPresent()
            && vde.getVariable(0).getInitializer().get()
                instanceof com.github.javaparser.ast.expr.IntegerLiteralExpr) {
          int start =
              ((com.github.javaparser.ast.expr.IntegerLiteralExpr)
                      vde.getVariable(0).getInitializer().get())
                  .asNumber()
                  .intValue();
          com.github.javaparser.ast.expr.BinaryExpr cmp =
              (com.github.javaparser.ast.expr.BinaryExpr) f.getCompare().get();
          if (cmp.getRight() instanceof com.github.javaparser.ast.expr.IntegerLiteralExpr) {
            int end =
                ((com.github.javaparser.ast.expr.IntegerLiteralExpr) cmp.getRight())
                    .asNumber()
                    .intValue();
            if (start > end) {
              int line = f.getBegin().map(p -> p.line).orElse(-1);
              int col = f.getBegin().map(p -> p.column).orElse(-1);
              ctx.report(
                  line,
                  col,
                  DEFAULT_RULE,
                  "For loop start (" + start + ") > end (" + end + "); loop never runs",
                  RuleDef.Severity.LOW,
                  "for");
            }
          }
        }
      }
    }
  }
}

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
/*
 * compareTo must return -1/0/1.
 *
 * <p><b>pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(MethodDeclaration)} (1 walk) + for each
 * method {@code m.findAll(ReturnStmt)} (1 walk per method).
 * The new implementation uses
 * {@code visit(MethodDeclaration)} dispatched by
 * {@code AstDispatcher} + a single
 * {@code m.walk(Node.class, ...)} walk. Total: 1 walk per
 * file (was 1 + M walks).</p>
 */
package org.plumb.style.bug;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class CompareToReturnValueCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-compareto-return-value", "COMPARETO_RETURN_VALUE"),
          "compareTo must return -1/0/1",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CompareToReturnValueCheck",
          true,
          List.of("ast-bug-compareto-return-value", "compareto-return-value"));

  @Override
  public String name() {
    return "compareto-return-value";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!m.getNameAsString().equals("compareTo")) return;
    m.walk(
        com.github.javaparser.ast.Node.class,
        node -> {
          if (node instanceof ReturnStmt ret
              && ret.getExpression().isPresent()
              && ret.getExpression().get() instanceof IntegerLiteralExpr) {
            int v = ((IntegerLiteralExpr) ret.getExpression().get()).asNumber().intValue();
            if (v < -1 || v > 1) {
              int line = ret.getBegin().map(p -> p.line).orElse(-1);
              int col = ret.getBegin().map(p -> p.column).orElse(-1);
              ctx.report(
                  line,
                  col,
                  DEFAULT_RULE,
                  "compareTo should return -1, 0, or 1 — got " + v,
                  RuleDef.Severity.LOW,
                  "compareTo");
            }
          }
        });
  }
}

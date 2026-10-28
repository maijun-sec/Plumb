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

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.type.PrimitiveType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** for-loop counter must not be float/double. */
public final class ForLoopVariableTypeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-for-loop-variable-type", "FOR_LOOP_VARIABLE_TYPE"),
          "for-loop counter must not be float/double",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-682"),
          "org.plumb.style.ForLoopVariableTypeCheck",
          true,
          List.of("ast-performance-loop-float", "for-loop-variable-type"));

  @Override
  public String name() {
    return "for-loop-variable-type";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    // ForStmt.getInitialization() returns NodeList<Expression>;
    // iterate directly.
    for (Expression init : n.getInitialization()) {
      if (!(init instanceof VariableDeclarationExpr vde)) continue;
      for (VariableDeclarator v : vde.getVariables()) {
        if (v.getType() instanceof PrimitiveType pt) {
          String code = pt.asString();
          if ("double".equals(code) || "float".equals(code)) {
            int line = v.getBegin().map(p -> p.line).orElse(-1);
            int col = v.getBegin().map(p -> p.column).orElse(-1);
            ctx.report(
                line,
                col,
                DEFAULT_RULE,
                "For-loop counter '" + v.getNameAsString() + "' is " + code + " : use int / long",
                RuleDef.Severity.MEDIUM,
                v.getNameAsString());
          }
        }
      }
    }
  }
}

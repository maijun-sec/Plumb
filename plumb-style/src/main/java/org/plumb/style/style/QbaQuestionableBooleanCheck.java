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

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** questionable boolean assignment (QBA_QUESTIONABLE_BOOLEAN_ASSIGNMENT). */
public final class QbaQuestionableBooleanCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-qba-questionable-boolean", "QBA_QUESTIONABLE_BOOLEAN"),
          "questionable boolean assignment (QBA_QUESTIONABLE_BOOLEAN_ASSIGNMENT)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-697"),
          "org.plumb.style.QbaQuestionableBooleanCheck",
          true,
          List.of("ast-style-questionable-boolean-assignment", "qba-questionable-boolean"));

  @Override
  public String name() {
    return "qba-questionable-boolean";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(AssignExpr n, StyleContext ctx) {
    if (n.getOperator() != AssignExpr.Operator.ASSIGN) return;
    // The RHS must be a binary comparison or a
    // conditional; the LHS must be a NameExpr
    // (we can't know its type without resolution,
    // so we narrow to a heuristic).
    if (!(n.getValue() instanceof BinaryExpr)) return;
    if (!(n.getTarget() instanceof com.github.javaparser.ast.expr.NameExpr)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Boolean comparison assigned : verify the variable type is boolean",
        RuleDef.Severity.MEDIUM,
        "=");
  }

  @Override
  public void visit(com.github.javaparser.ast.stmt.ExpressionStmt n, StyleContext ctx) {
    if (!(n.getExpression() instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr))
      return;
    com.github.javaparser.ast.expr.VariableDeclarationExpr vde =
        (com.github.javaparser.ast.expr.VariableDeclarationExpr) n.getExpression();
    for (com.github.javaparser.ast.body.VariableDeclarator v : vde.getVariables()) {
      if (!v.getInitializer().isPresent()) continue;
      com.github.javaparser.ast.expr.Expression init = v.getInitializer().get();
      // Unwrap any parens.
      while (init instanceof com.github.javaparser.ast.expr.EnclosedExpr ee) {
        init = ee.getInner();
      }
      if (!(init instanceof BinaryExpr)) continue;
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Boolean comparison assigned : verify the variable type is boolean",
          RuleDef.Severity.MEDIUM,
          "=");
    }
  }
}

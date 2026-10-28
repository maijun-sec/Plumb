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
import com.github.javaparser.ast.stmt.ExpressionStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** variable is assigned but never used (or overwritten immediately). */
public final class UnusedAssignmentCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unused-assignment", "UNUSED_ASSIGNMENT"),
          "variable is assigned but never used (or overwritten immediately)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-563"),
          "org.plumb.style.UnusedAssignmentCheck",
          true,
          List.of("ast-style-unused-assignment", "unused-assignment"));

  @Override
  public String name() {
    return "unused-assignment";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ExpressionStmt n, StyleContext ctx) {
    if (!(n.getExpression() instanceof AssignExpr a)) return;
    if (a.getOperator() != AssignExpr.Operator.ASSIGN) return;
    // `x =...;` is suspicious only if x was declared
    // on the previous statement and never read in
    // between. We can't easily track reads, so we
    // simply flag any assignment to a name.
    // (This is a noisy check; users can suppress via
    // SuppressWarnings.)
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String target = a.getTarget().toString();
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Assignment to '" + target + "' : verify the value is used",
        RuleDef.Severity.LOW,
        target);
  }
}

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
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** variable should not be self-assigned (no-op, likely copy-paste bug). */
public final class SelfAssignmentCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-self-assignment", "SELF_ASSIGNMENT"),
          "variable should not be self-assigned (no-op, likely copy-paste bug)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-682"),
          "org.plumb.style.SelfAssignmentCheck",
          true,
          List.of("ast-style-assignment-self", "self-assignment"));

  @Override
  public String name() {
    return "self-assignment";
  }

  @Override
  public void init(StyleContext ctx) {
    // no per-file state needed
  }

  @Override
  public void visit(ExpressionStmt n, StyleContext ctx) {
    final Expression e = n.getExpression();
    if (!(e instanceof AssignExpr assign)) return;
    // We only handle simple `=` (not `+=`, `-=`, etc.)
    if (assign.getOperator() != AssignExpr.Operator.ASSIGN) return;
    // LHS and RHS must be the same simple name. Field
    // accesses (`this.x = x`) are NOT self-assignment
    // unless the receiver is also the same name (which
    // would be `x = x` again, just rewritten). So we
    // only fire when both sides are bare NameExpr and
    // they have the same name.
    if (!(assign.getTarget() instanceof NameExpr target)) return;
    if (!(assign.getValue() instanceof NameExpr value)) return;
    if (!target.getNameAsString().equals(value.getNameAsString())) return;
    ctx.report(
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Self-assignment of variable '"
            + target.getNameAsString()
            + "' (no-op, likely copy-paste bug)",
        RuleDef.Severity.MEDIUM,
        target.getNameAsString());
  }
}

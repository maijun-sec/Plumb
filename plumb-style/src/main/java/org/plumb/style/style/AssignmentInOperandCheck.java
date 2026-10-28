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
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** assignment in operand : separate from condition. */
public final class AssignmentInOperandCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-assign-in-operand", "ASSIGN_IN_OPERAND"),
          "assignment in operand : separate from condition",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-783"),
          "org.plumb.style.AssignmentInOperandCheck",
          true,
          List.of("ast-style-assignment-in-operand", "assign-in-operand"));

  @Override
  public String name() {
    return "assign-in-operand";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    checkCondition(n.getCondition(), ctx);
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    checkCondition(n.getCondition(), ctx);
  }

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    n.getCompare().ifPresent(c -> checkCondition(c, ctx));
  }

  @Override
  public void visit(DoStmt n, StyleContext ctx) {
    checkCondition(n.getCondition(), ctx);
  }

  private void checkCondition(com.github.javaparser.ast.expr.Expression c, StyleContext ctx) {
    if (c == null) return;
    for (AssignExpr a : c.findAll(AssignExpr.class)) {
      int line = a.getBegin().map(p -> p.line).orElse(-1);
      int col = a.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Assignment in condition : extract to a statement",
          RuleDef.Severity.MEDIUM,
          "=");
    }
  }
}

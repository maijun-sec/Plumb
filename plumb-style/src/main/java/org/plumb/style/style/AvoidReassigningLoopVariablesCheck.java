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

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid reassigning the loop counter inside the body. */
public final class AvoidReassigningLoopVariablesCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-reassign-loop-var", "REASSIGN_LOOP_VAR"),
          "avoid reassigning the loop counter inside the body",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-563"),
          "org.plumb.style.AvoidReassigningLoopVariablesCheck",
          true,
          List.of("ast-style-reassigning-loop-variables", "reassign-loop-var"));

  @Override
  public String name() {
    return "reassign-loop-var";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    // Find the counter name.
    for (var init : n.getInitialization()) {
      if (!(init instanceof VariableDeclarationExpr vde)) continue;
      for (VariableDeclarator v : vde.getVariables()) {
        String name = v.getNameAsString();
        checkReassign(n, name, ctx);
      }
    }
  }

  @Override
  public void visit(ForEachStmt n, StyleContext ctx) {
    String name = n.getVariable().getVariables().get(0).getNameAsString();
    checkReassign(n, name, ctx);
  }

  private void checkReassign(com.github.javaparser.ast.Node n, String varName, StyleContext ctx) {
    n.findAll(AssignExpr.class).stream()
        .filter(a -> a.getTarget() instanceof NameExpr ne && ne.getNameAsString().equals(varName))
        .forEach(
            a -> {
              int line = a.getBegin().map(p -> p.line).orElse(-1);
              int col = a.getBegin().map(p -> p.column).orElse(-1);
              ctx.report(
                  line,
                  col,
                  DEFAULT_RULE,
                  "Reassigning loop variable '" + varName + "'",
                  RuleDef.Severity.MEDIUM,
                  varName);
            });
  }
}

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
 * catch block parameter should not be reassigned.
 *
 * <p><b>pattern A rewrite.</b> The previous
 * implementation called {@code n.findAll(CatchClause.class)} +
 * for EACH catch {@code cc.findAll(AssignExpr.class)} -- N·M walks
 * per file. The new implementation uses
 * {@code visit(CatchClause)} (dispatched by AstDispatcher once
 * per catch) + a single {@code cc.walk(AssignExpr, ...)} per
 * catch. Total: 1 walk per file (was 1 + N walks).</p>
 */
package org.plumb.style.error;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class AssignmentToCatchBlockParameterCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(
              "ast-error-assignmenttocatchblockparameter", "ASSIGNMENTTOCATCHBLOCKPARAMETER"),
          "catch block parameter should not be reassigned",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.AssignmentToCatchBlockParameterCheck",
          true,
          List.of("ast-error-assign-catch-var", "assignmenttocatchblockparametercheck"));

  @Override
  public String name() {
    return "assignmenttocatchblockparameter";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CatchClause cc, StyleContext ctx) {
    String name = cc.getParameter().getNameAsString();
    cc.walk(
        AssignExpr.class,
        ae -> {
          if (ae.getTarget() instanceof NameExpr ne && ne.getNameAsString().equals(name)) {
            int line = ae.getBegin().map(p -> p.line).orElse(-1);
            int col = ae.getBegin().map(p -> p.column).orElse(-1);
            ctx.report(
                line,
                col,
                DEFAULT_RULE,
                "Catch parameter " + name + " reassigned",
                RuleDef.Severity.MEDIUM,
                name);
          }
        });
  }
}

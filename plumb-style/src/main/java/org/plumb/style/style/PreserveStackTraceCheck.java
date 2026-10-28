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

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** throw new Exception() loses the original stack trace. */
public final class PreserveStackTraceCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-preserve-stack", "PRESERVE_STACK"),
          "throw new Exception() loses the original stack trace",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-209"),
          "org.plumb.style.PreserveStackTraceCheck",
          true,
          List.of("ast-style-preserve-stack-trace", "preserve-stack"));

  private Set<String> catchParamNames = new HashSet<>();

  @Override
  public String name() {
    return "preserve-stack";
  }

  @Override
  public void init(StyleContext ctx) {
    catchParamNames.clear();
  }

  @Override
  public void finish(StyleContext ctx) {
    catchParamNames.clear();
  }

  // P1-1-2026-10-18: reset catchParamNames at the start of each method to
  // prevent cross-method leak. See AvoidPrintStackTraceCheck for the same fix.
  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    catchParamNames.clear();
  }

  @Override
  public void visit(CatchClause n, StyleContext ctx) {
    catchParamNames.add(n.getParameter().getNameAsString());
  }

  @Override
  public void visit(ThrowStmt n, StyleContext ctx) {
    if (n.findAncestor(CatchClause.class).isEmpty()) return;
    if (n.getExpression() == null) return;
    if (!(n.getExpression() instanceof ObjectCreationExpr oc)) return;
    // The new exception must reference the original
    // catch parameter (or be a rethrow `throw e`).
    boolean includesCatchParam = false;
    for (var arg : oc.getArguments()) {
      if (arg instanceof com.github.javaparser.ast.expr.NameExpr ne
          && catchParamNames.contains(ne.getNameAsString())) {
        includesCatchParam = true;
        break;
      }
    }
    if (includesCatchParam) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "throw new "
            + oc.getType().getNameAsString()
            + "() does not include original exception as cause",
        RuleDef.Severity.MEDIUM,
        "throw");
  }
}

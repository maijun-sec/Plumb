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

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** result of comparison-style method call is ignored. */
public final class ResultOfMethodCallIgnoredCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-ignored-result", "IGNORED_RESULT"),
          "result of comparison-style method call is ignored",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-252"),
          "org.plumb.style.ResultOfMethodCallIgnoredCheck",
          true,
          List.of("ast-style-ignored-result-ast", "ignored-result"));

  /**
   * The set of method names that look like "comparison / predicate" methods whose return value
   * should always be used. Pre- : keep narrow whitelist.
   */
  private static final java.util.Set<String> COMPARISON_NAMES =
      java.util.Set.of(
          "equals",
          "startsWith",
          "endsWith",
          "contains",
          "isEmpty",
          "isBlank",
          "matches",
          "regionMatches");

  @Override
  public String name() {
    return "ignored-result";
  }

  @Override
  public void init(StyleContext ctx) {
    // no per-file state needed
  }

  @Override
  public void visit(ExpressionStmt n, StyleContext ctx) {
    // The expression must be a MethodCallExpr (not
    // an ObjectCreationExpr or a binary expression).
    if (!(n.getExpression() instanceof MethodCallExpr call)) return;
    // Method name must be in our narrow whitelist.
    if (!COMPARISON_NAMES.contains(call.getNameAsString())) return;
    // The call must NOT have a chained.method() that
    // would make the discarded result less obvious
    // (e.g. `x.equals(y).foo()` is fine because foo
    // uses the result). For our narrow check, we
    // require that the call's parent is the
    // ExpressionStmt itself (i.e. the call is the
    // statement-level expression, with no further
    // chaining). JavaParser's parent() tells us
    // that.
    if (call.getParentNode().isEmpty()) return;
    if (call.getParentNode().get() != n) return;
    ctx.report(
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Result of '"
            + call.getNameAsString()
            + "' call is ignored; did you mean to use the return value?",
        RuleDef.Severity.MEDIUM,
        call.getNameAsString());
  }
}

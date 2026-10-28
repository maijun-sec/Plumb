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

import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** do not call System.gc() / Runtime.gc(). */
public final class DoNotCallGarbageCollectionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-do-not-call-gc", "DO_NOT_CALL_GC"),
          "do not call System.gc() / Runtime.gc()",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-401"),
          "org.plumb.style.DoNotCallGarbageCollectionCheck",
          true,
          List.of("ast-performance-not-call-gc", "do-not-call-gc"));

  @Override
  public String name() {
    return "do-not-call-gc";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"gc".equals(n.getNameAsString())) return;
    if (!isSystemOrRuntimeScope(n.getScope().orElse(null))) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Avoid System.gc() : let the JVM manage collection",
        RuleDef.Severity.MEDIUM,
        "gc");
  }

  private boolean isSystemOrRuntimeScope(com.github.javaparser.ast.expr.Expression scope) {
    if (scope instanceof FieldAccessExpr fa) {
      String owner = fa.getScope().toString();
      String field = fa.getNameAsString();
      return ("System".equals(owner) && "gc".equals(field))
          || ("Runtime".equals(owner) && "gc".equals(field));
    }
    if (scope instanceof NameExpr ne) {
      return "System".equals(ne.getNameAsString()) || "Runtime".equals(ne.getNameAsString());
    }
    return false;
  }
}

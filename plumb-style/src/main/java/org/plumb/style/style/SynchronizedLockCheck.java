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

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** synchronized on a non-final field : use a private final lock. */
public final class SynchronizedLockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-synchronized-lock", "SYNCHRONIZED_LOCK"),
          "synchronized on a non-final field : use a private final lock",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-820"),
          "org.plumb.style.SynchronizedLockCheck",
          true,
          List.of("ast-style-synchronized-lock", "synchronized-lock"));

  @Override
  public String name() {
    return "synchronized-lock";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SynchronizedStmt n, StyleContext ctx) {
    Expression lock = n.getExpression();
    String name = exprName(lock);
    if (name == null) return;
    // Without symbol resolution we can't tell if the
    // referenced name is a `final` field. We narrow
    // to the most common pattern: `synchronized(this)`
    // (lock on the instance itself : often a bug).
    if ("this".equals(name)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "synchronized(this) : locks the public instance; " + "use a private final lock object",
          RuleDef.Severity.MEDIUM,
          "this");
      return;
    }
    // `synchronized(Type.class)` is fine.
    if (name.endsWith(".class")) return;
    // `synchronized(getClass())` is fine.
    if (lock instanceof MethodCallExpr
        || (lock instanceof NameExpr && Character.isUpperCase(name.charAt(0)))) {
      // Probably a class lock or static final field.
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "synchronized on '" + name + "' : make sure the lock object is final",
        RuleDef.Severity.MEDIUM,
        name);
  }

  private static String exprName(Expression e) {
    if (e instanceof NameExpr ne) return ne.getNameAsString();
    if (e instanceof FieldAccessExpr fa) return fa.getNameAsString();
    return null;
  }
}

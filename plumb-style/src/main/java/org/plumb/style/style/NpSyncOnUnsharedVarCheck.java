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
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** synchronized on a local variable (NP_SYNC_INVOKED_ON_UNSHARED_VARIABLE). */
public final class NpSyncOnUnsharedVarCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-np-sync-unshared", "NP_SYNC_UNSHARED"),
          "synchronized on a local variable (NP_SYNC_INVOKED_ON_UNSHARED_VARIABLE)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-820"),
          "org.plumb.style.NpSyncOnUnsharedVarCheck",
          true,
          List.of("ast-style-sync-invoked-on-unshared-variable", "np-sync-unshared"));

  @Override
  public String name() {
    return "np-sync-unshared";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SynchronizedStmt n, StyleContext ctx) {
    Expression lock = n.getExpression();
    // Skip: this, class lock.
    if (lock instanceof NameExpr ne) {
      String name = ne.getNameAsString();
      if ("this".equals(name)) return;
      if (Character.isUpperCase(name.charAt(0))) return; // class
      // A simple-typed local : likely a
      // newly-allocated local. Flag it.
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "synchronized on a local variable : the lock " + "is on a per-call object",
          RuleDef.Severity.MEDIUM,
          name);
    }
  }
}

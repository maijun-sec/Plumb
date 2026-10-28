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

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** unnecessary instanceof check. */
public final class SuperfluousInstanceofCheck implements AstChecker {

  private StyleContext ctx;
  private RuleDef rule;

  @Override
  public String name() {
    return "sio-superfluous-instanceof";
  }

  @Override
  public void init(StyleContext ctx) {
    this.ctx = ctx;
    this.rule = ctx.rules().byId(RuleRegistry.SUPERFLUOUS_INSTANCEOF).orElse(null);
  }

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    Expression cond = n.getCondition();
    if (!(cond instanceof InstanceOfExpr ioe)) return;
    Type targetType = ioe.getType();
    String targetName = targetType.asString();
    if (targetName.isEmpty()) return;
    Statement then = n.getThenStmt();
    if (then == null) return;
    Node searchRoot = then;
    if (then instanceof BlockStmt block) {
      searchRoot = block;
    }
    RedundantCastFinder finder = new RedundantCastFinder(targetName);
    searchRoot.accept(finder, null);
    if (finder.found) {
      int line = n.getRange().map(r -> r.begin.line).orElse(-1);
      int col = n.getRange().map(r -> r.begin.column).orElse(-1);
      this.ctx.report(
          line,
          col,
          this.rule,
          "redundant cast '("
              + targetName
              + ") x' inside an instanceof-typed branch; "
              + "the instanceof check already guarantees the type",
          RuleDef.Severity.LOW,
          "if (x instanceof " + targetName + ") { (" + targetName + ") x;... }");
    }
  }

  private static final class RedundantCastFinder extends VoidVisitorAdapter<Void> {
    private final String targetName;
    private boolean found = false;

    RedundantCastFinder(String targetName) {
      this.targetName = targetName;
    }

    @Override
    public void visit(CastExpr n, Void arg) {
      if (found) return;
      String castType = n.getType().asString();
      if (castType.equals(targetName)) {
        found = true;
        return;
      }
      super.visit(n, arg);
    }
  }
}

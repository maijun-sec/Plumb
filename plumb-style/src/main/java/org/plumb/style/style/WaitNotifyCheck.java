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
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * wait()/notify() should be in a loop within a synchronized block.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `requireSyncBlock` | BOOLEAN | `true` | Also require enclosing synchronized block |
 */
public final class WaitNotifyCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "requireSyncBlock",
        "Also require enclosing synchronized block",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-wait-notify-in-loop", "WAIT_NOTIFY_IN_LOOP"),
          "wait()/notify() should be in a loop within a synchronized block",
          RuleDef.Severity.HIGH,
          RuleDef.Type.MULTITHREADING,
          List.of("CWE-662", "CWE-833"),
          "org.plumb.style.WaitNotifyCheck",
          true,
          List.of("ast-style-wait-notify-in-loop", "wait-notify-in-loop"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "wait-notify-in-loop";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    boolean requireSync = paramBool(ctx, "requireSyncBlock", true);
    for (MethodCallExpr call : n.getBody().get().findAll(MethodCallExpr.class)) {
      String name = call.getNameAsString();
      if (!name.equals("wait") && !name.equals("notify") && !name.equals("notifyAll")) {
        continue;
      }
      // Must be inside a while/do-while/for loop
      boolean inLoop =
          call.findAncestor(WhileStmt.class).isPresent()
              || call.findAncestor(DoStmt.class).isPresent()
              || call.findAncestor(ForStmt.class).isPresent();
      // Must be inside a synchronized block, OR the enclosing
      // method itself is synchronized (a common pattern for
      // wait/notify on `this`).
      boolean inSync =
          !requireSync
              || call.findAncestor(SynchronizedStmt.class).isPresent()
              || n.isSynchronized();
      if (!inLoop || !inSync) {
        int line = call.getBegin().map(p -> p.line).orElse(-1);
        int col = call.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "'"
                + name
                + "()' must be in a while/do/for loop"
                + (requireSync ? " inside a synchronized block or method" : ""),
            RuleDef.Severity.HIGH,
            name);
      }
    }
  }

  private static boolean paramBool(StyleContext ctx, String name, boolean def) {
    Boolean override = ctx.paramOverride(name, Boolean.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.boolDefault();
    }
    return def;
  }
}

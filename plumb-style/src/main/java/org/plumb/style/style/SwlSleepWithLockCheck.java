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
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Thread.sleep inside synchronized (SWL_SLEEP_WITH_LOCK_HELD). */
public final class SwlSleepWithLockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-swl-sleep-lock", "SWL_SLEEP_LOCK"),
          "Thread.sleep inside synchronized (SWL_SLEEP_WITH_LOCK_HELD)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-820"),
          "org.plumb.style.SwlSleepWithLockCheck",
          true,
          List.of("ast-style-sleep-with-lock-held", "swl-sleep-lock"));

  @Override
  public String name() {
    return "swl-sleep-lock";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"sleep".equals(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    String scope = n.getScope().get().toString();
    if (!scope.endsWith("Thread") && !"currentThread".equals(scope)) return;
    // Walk up to see if we're inside a synchronized block.
    com.github.javaparser.ast.Node parent = n.getParentNode().orElse(null);
    while (parent != null
        && !(parent instanceof SynchronizedStmt)
        && !(parent instanceof com.github.javaparser.ast.body.MethodDeclaration)
        && !(parent instanceof com.github.javaparser.ast.body.ConstructorDeclaration)) {
      parent = parent.getParentNode().orElse(null);
    }
    boolean inSync = parent instanceof SynchronizedStmt;
    if (!inSync && parent instanceof com.github.javaparser.ast.body.MethodDeclaration) {
      com.github.javaparser.ast.body.MethodDeclaration md =
          (com.github.javaparser.ast.body.MethodDeclaration) parent;
      inSync =
          md.getModifiers().stream()
              .anyMatch(
                  m -> m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.SYNCHRONIZED);
    }
    if (inSync) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Thread.sleep inside synchronized : releases the lock for " + "its duration",
          RuleDef.Severity.MEDIUM,
          "sleep");
    }
  }
}

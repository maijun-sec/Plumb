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
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** test method should include at least one assertion. */
public final class ReachableAssertionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-reachable-assertion", "REACHABLE_ASSERTION"),
          "test method should include at least one assertion",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.ReachableAssertionCheck",
          true,
          List.of("ast-style-reachable-assertion", "reachable-assertion"));

  @Override
  public String name() {
    return "reachable-assertion";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(com.github.javaparser.ast.body.MethodDeclaration n, StyleContext ctx) {
    if (!isTestMethod(n)) return;
    if (n.getBody().isEmpty()) return;
    boolean hasAssertion =
        n.findAll(MethodCallExpr.class).stream()
            .anyMatch(
                m -> {
                  String name = m.getNameAsString();
                  return name.startsWith("assert") || name.equals("fail") || name.equals("verify");
                });
    if (!hasAssertion) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Test method '" + n.getNameAsString() + "' has no assertion",
          RuleDef.Severity.MEDIUM,
          n.getNameAsString());
    }
  }

  private boolean isTestMethod(com.github.javaparser.ast.body.MethodDeclaration n) {
    if (n.getNameAsString().startsWith("test")) return true;
    return n.getAnnotations().stream()
        .anyMatch(a -> a.getNameAsString().equals("Test") || a.getNameAsString().endsWith(".Test"));
  }
}

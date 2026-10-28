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
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * clone() must call super.clone().
 *
 * <p>Pattern D per-method visit. If method name is {@code "clone"} and no descendant {@code
 * super.clone()} call, fire.
 */
public final class SuperCloneCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-super-clone", "SUPER_CLONE"),
          "clone() must call super.clone()",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-668"),
          "org.plumb.style.SuperCloneCheck",
          true,
          List.of("ast-style-clone", "super-clone"));

  @Override
  public String name() {
    return "super-clone";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!m.getNameAsString().equals("clone")) return;
    boolean[] hasSuper = {false};
    if (m.getBody().isPresent()) {
      m.getBody()
          .get()
          .walk(
              Node.class,
              n -> {
                if (n instanceof MethodCallExpr) {
                  MethodCallExpr mc = (MethodCallExpr) n;
                  if (mc.getScope().isPresent()
                      && mc.getScope().get().toString().equals("super")
                      && mc.getNameAsString().equals("clone")) {
                    hasSuper[0] = true;
                  }
                }
              });
    }
    if (!hasSuper[0]) {
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "clone() should call super.clone()",
          RuleDef.Severity.MEDIUM,
          "clone");
    }
  }
}

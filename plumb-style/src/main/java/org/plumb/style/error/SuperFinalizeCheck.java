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
package org.plumb.style.error;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * finalize() must call super.finalize().
 *
 * <p>Pattern D per-method visit. If method name is {@code "finalize"} and no descendant {@code
 * super.finalize()} call, fire (preserves original behavior for abstract / native methods which
 * lack a body).
 */
public final class SuperFinalizeCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-super-finalize", "SUPER_FINALIZE"),
          "finalize() must call super.finalize()",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-568"),
          "org.plumb.style.SuperFinalizeCheck",
          true,
          List.of("ast-error-finalize", "super-finalize"));

  @Override
  public String name() {
    return "super-finalize";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!m.getNameAsString().equals("finalize")) return;
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
                      && mc.getNameAsString().equals("finalize")) {
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
          "finalize() should call super.finalize()",
          RuleDef.Severity.MEDIUM,
          "finalize");
    }
  }
}

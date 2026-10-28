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
package org.plumb.style.bug;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Comparing with null using {@code .equals(null)} -- should be {@code ==}.
 *
 * <p>M5 part-2 migration (2026-10-17): the previous implementation used {@code
 * visit(CompilationUnit) + findAll(MethodCallExpr.class)}, which costs one full-AST walk per file
 * just to find {@code MethodCallExpr} nodes -- wasted work because the dispatcher already gives us
 * a {@code visit(MethodCallExpr)} callback that fires once per {@code MethodCallExpr} with no extra
 * walking. This rewrite uses the per-node-type callback directly; behaviour is unchanged.
 */
public final class EqualsNullCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-equals-null", "EQUALS_NULL"),
          "comparing with null using .equals() (should use ==)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EqualsNullCheck",
          true,
          List.of("ast-bug-equals-null", "equals-null"));

  @Override
  public String name() {
    return "equals-null";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr mc, StyleContext ctx) {
    if (!"equals".equals(mc.getNameAsString())) return;
    if (mc.getArguments().size() != 1) return;
    if (!(mc.getArgument(0) instanceof NullLiteralExpr)) return;
    int line = mc.getBegin().map(p -> p.line).orElse(-1);
    int col = mc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use == for null comparison; .equals(null) is always false",
        RuleDef.Severity.LOW,
        mc.toString());
  }
}

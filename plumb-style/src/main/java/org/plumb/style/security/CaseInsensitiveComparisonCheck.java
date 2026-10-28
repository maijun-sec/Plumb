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
package org.plumb.style.security;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Case-insensitive {@code String} comparison should use {@code equalsIgnoreCase} rather than {@code
 * toLowerCase()/toUpperCase() + equals()}.
 */
public final class CaseInsensitiveComparisonCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-case-insensitive-comparison", "CASE_INSENSITIVE_COMPARISON"),
          "case-insensitive String comparison should use equalsIgnoreCase",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CaseInsensitiveComparisonCheck",
          true,
          List.of("ast-security-insensitive-comparison", "case-insensitive-comparison"));

  @Override
  public String name() {
    return "case-insensitive-comparison";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr mc, StyleContext ctx) {
    if ("equalsIgnoreCase".equals(mc.getNameAsString())) return;
    if (!"equals".equals(mc.getNameAsString())) return;
    if (mc.getScope().isEmpty()) return;
    String scope = mc.getScope().get().toString();
    if (!scope.endsWith(".toLowerCase()") && !scope.endsWith(".toUpperCase()")) return;
    int line = mc.getBegin().map(p -> p.line).orElse(-1);
    int col = mc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use equalsIgnoreCase instead of toLowerCase/toUpperCase + equals",
        RuleDef.Severity.LOW,
        mc.toString());
  }
}

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

import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-613 (Insufficient Session Expiration).
 *
 * <p>Fires when {@code HttpSession.setMaxInactiveInterval(int)} is called with a value {@code <=
 * 0}.
 */
public final class InsufficientSessionExpirationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(
              "ast-style-insufficient-session-expiration", "INSUFFICIENT_SESSION_EXPIRATION"),
          "HttpSession.setMaxInactiveInterval with non-positive value (CWE-613).",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-613"),
          "org.plumb.style.InsufficientSessionExpirationCheck",
          true,
          List.of(
              "ast-style-insufficient-session-expiration-style",
              "insufficient-session-expiration"));

  @Override
  public String name() {
    return "insufficient-session-expiration";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr mc, StyleContext ctx) {
    if (!"setMaxInactiveInterval".equals(mc.getNameAsString())) {
      return;
    }
    if (mc.getArguments().size() != 1) return;
    Long argValue = literalValue(mc.getArguments().get(0));
    if (argValue == null) return;
    if (argValue > 0) return;
    int line = mc.getBegin().map(p -> p.line).orElse(-1);
    ctx.report(
        line,
        -1,
        DEFAULT_RULE,
        "setMaxInactiveInterval called with "
            + argValue
            + " at line "
            + line
            + " (CWE-613: < 0 means never expire, 0"
            + " means expire at end of every"
            + " request — both unsafe for an"
            + " authenticated session).",
        RuleDef.Severity.MEDIUM,
        "setMaxInactiveInterval@" + argValue);
  }

  private static Long literalValue(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof UnaryExpr ue) {
      Long inner = literalValue(ue.getExpression());
      if (inner == null) return null;
      if (ue.getOperator() == UnaryExpr.Operator.MINUS) return -inner;
      if (ue.getOperator() == UnaryExpr.Operator.PLUS) return inner;
      return null;
    }
    if (e instanceof IntegerLiteralExpr il) {
      try {
        return Long.parseLong(il.getValue());
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    if (e instanceof LongLiteralExpr ll) {
      try {
        String v = ll.getValue();
        if (v.endsWith("L") || v.endsWith("l")) v = v.substring(0, v.length() - 1);
        return Long.parseLong(v);
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }
}

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
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid octal literals (start with 0). */
public final class AvoidUsingOctalValuesCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-avoid-octal-values", "AVOID_OCTAL_VALUES"),
          "avoid octal literals (start with 0)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-682"),
          "org.plumb.style.AvoidUsingOctalValuesCheck",
          true,
          List.of("ast-style-octal-values", "avoid-octal-values"));

  @Override
  public String name() {
    return "avoid-octal-values";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IntegerLiteralExpr n, StyleContext ctx) {
    String v = n.getValue();
    // Octal: starts with 0 and is not just "0" and
    // contains only digits 0-7.
    if (v.length() > 1
        && v.startsWith("0")
        && !v.startsWith("0x")
        && !v.startsWith("0X")
        && !v.startsWith("0b")
        && !v.startsWith("0B")) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Octal literal '" + v + "' is confusing : use 0x prefix or decimal",
          RuleDef.Severity.MEDIUM,
          v);
    }
  }
}

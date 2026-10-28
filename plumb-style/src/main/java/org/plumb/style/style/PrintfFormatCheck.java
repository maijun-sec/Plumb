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
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** String.format / printf : verify format and arg count. */
public final class PrintfFormatCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-printf-format", "PRINTF_FORMAT"),
          "String.format / printf : verify format and arg count",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.PrintfFormatCheck",
          true,
          List.of("ast-style-printf-format", "printf-format"));

  @Override
  public String name() {
    return "printf-format";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    String name = n.getNameAsString();
    if (!"format".equals(name) && !"printf".equals(name)) return;
    if (n.getArguments().isEmpty()) return;
    // First arg must be a String literal.
    if (!(n.getArgument(0) instanceof StringLiteralExpr sl)) return;
    String fmt = sl.getValue();
    int specifiers = countSpecifiers(fmt);
    int args = n.getArguments().size() - 1;
    if (specifiers == 0) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "format() called with no format specifiers",
          RuleDef.Severity.LOW,
          "format");
    } else if (specifiers != args) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "format specifier count (" + specifiers + ") != argument count (" + args + ")",
          RuleDef.Severity.LOW,
          "format");
    }
  }

  private static int countSpecifiers(String fmt) {
    int count = 0;
    int i = 0;
    while (i < fmt.length()) {
      char c = fmt.charAt(i);
      if (c == '\\' && i + 1 < fmt.length()) {
        i += 2;
        continue;
      }
      if (c == '%' && i + 1 < fmt.length() && fmt.charAt(i + 1) != '%') {
        count++;
      }
      i++;
    }
    return count;
  }
}

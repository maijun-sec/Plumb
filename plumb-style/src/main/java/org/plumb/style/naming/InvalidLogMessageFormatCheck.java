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
package org.plumb.style.naming;

import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** log message lacks placeholder : pass arguments to slf4j. */
public final class InvalidLogMessageFormatCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-invalid-log", "INVALID_LOG"),
          "log message lacks placeholder : pass arguments to slf4j",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.InvalidLogMessageFormatCheck",
          true,
          List.of("ast-naming-invalid-log-message-format", "invalid-log"));

  @Override
  public String name() {
    return "invalid-log";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (n.getScope().isEmpty()) return;
    String logMethod = n.getNameAsString();
    if (!"info".equals(logMethod) && !"debug".equals(logMethod) && !"trace".equals(logMethod))
      return;
    // Verify the scope looks like a logger (either
    // a FieldAccessExpr on `LOG` / `log` / `logger`,
    // or a method chain like `LoggerFactory.getLogger(...).info(...)`).
    com.github.javaparser.ast.expr.Expression scope = n.getScope().get();
    if (scope instanceof FieldAccessExpr fa) {
      String owner = fa.getScope().toString();
      if (!owner.toLowerCase().endsWith("log") && !owner.equals("LOG") && !owner.endsWith(".LOG")) {
        // Might be a logger field like `LOG.info(...)`.
        String n1 = fa.getNameAsString();
        if (!"LOG".equals(n1) && !"log".equals(n1) && !"logger".equals(n1)) {
          return;
        }
      }
    } else if (scope instanceof MethodCallExpr m) {
      // Chain like `LoggerFactory.getLogger(...).info(...)`.
      String parentName = m.getNameAsString();
      if (!"getLogger".equals(parentName)
          && !"getRootLogger".equals(parentName)
          && !"getLog".equals(parentName)) {
        return;
      }
    } else {
      return;
    }
    // The first arg is the format string. Look for
    // a {} placeholder.
    if (n.getArguments().isEmpty()) return;
    if (!(n.getArgument(0) instanceof StringLiteralExpr s)) return;
    if (s.getValue().contains("{}") || s.getValue().contains("{0}")) return;
    // Heuristic: only fire when there are extra args
    // (suggesting the dev meant to format).
    if (n.getArguments().size() < 2) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "log message lacks {} placeholder : slf4j won't format it",
        RuleDef.Severity.LOW,
        logMethod);
  }
}

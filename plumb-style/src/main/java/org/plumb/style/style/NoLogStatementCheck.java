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
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** log call may be at a disabled level. */
public final class NoLogStatementCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-no-log-statement", "NO_LOG_STATEMENT"),
          "log call may be at a disabled level",
          RuleDef.Severity.LOW,
          RuleDef.Type.PERFORMANCE,
          List.of(),
          "org.plumb.style.NoLogStatementCheck",
          true,
          List.of("ast-style-log-at-disabled-level", "no-log-statement"));

  private static final Set<String> LOG_METHODS =
      Set.of("trace", "debug", "info", "warn", "error", "fatal");

  @Override
  public String name() {
    return "no-log-statement";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    String name = n.getNameAsString();
    if (!LOG_METHODS.contains(name)) return;
    if (n.getScope().isEmpty()) return;
    String scopeText = n.getScope().get().toString();
    // Heuristic: the receiver is named "log", "LOG",
    // "LOGGER", or has a Logger type.
    if (!(scopeText.contains("log") || scopeText.contains("LOG") || scopeText.contains("Logger"))) {
      return;
    }
    // We can't determine the actual logger level
    // statically, so just flag the call and let the
    // reader decide. The intent is to make the
    // "is this logger at TRACE?" question explicit.
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "log." + name + "() call : confirm the level is enabled",
        RuleDef.Severity.LOW,
        name);
  }
}

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

import com.github.javaparser.ast.stmt.*;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** control statement should always use braces. */
public final class ControlStatementBracesCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-control-statement-braces", "CONTROL_STATEMENT_BRACES"),
          "control statement should always use braces",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ControlStatementBracesCheck",
          true,
          List.of("ast-style-braces", "control-statement-braces"));

  @Override
  public String name() {
    return "control-statement-braces";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    check(n.getThenStmt(), "if", ctx);
    check(n.getElseStmt().orElse(null), "else", ctx);
  }

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    check(n.getBody(), "for", ctx);
  }

  @Override
  public void visit(ForEachStmt n, StyleContext ctx) {
    check(n.getBody(), "for-each", ctx);
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    check(n.getBody(), "while", ctx);
  }

  private void check(Statement body, String kind, StyleContext ctx) {
    if (body == null) return;
    if (body instanceof com.github.javaparser.ast.stmt.BlockStmt) return;
    int line = body.getBegin().map(p -> p.line).orElse(-1);
    int col = body.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        kind + " statement should use braces { }",
        RuleDef.Severity.LOW,
        kind);
  }
}

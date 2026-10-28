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

/** loop has multiple break/continue : refactor. */
public final class LoopsWithMultipleBreaksCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-loop-multi-breaks", "LOOP_MULTI_BREAKS"),
          "loop has multiple break/continue : refactor",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LoopsWithMultipleBreaksCheck",
          true,
          List.of("ast-style-multiple-breaks", "loop-multi-breaks"));

  @Override
  public String name() {
    return "loop-multi-breaks";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    check(n, "for", ctx);
  }

  @Override
  public void visit(ForEachStmt n, StyleContext ctx) {
    check(n, "for-each", ctx);
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    check(n, "while", ctx);
  }

  @Override
  public void visit(DoStmt n, StyleContext ctx) {
    check(n, "do", ctx);
  }

  private void check(com.github.javaparser.ast.Node loop, String kind, StyleContext ctx) {
    long breaks = loop.findAll(BreakStmt.class).size() + loop.findAll(ContinueStmt.class).size();
    if (breaks <= 1) return;
    int line = loop.getBegin().map(p -> p.line).orElse(-1);
    int col = loop.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        kind + " loop has " + breaks + " break/continue : refactor",
        RuleDef.Severity.LOW,
        kind);
  }
}

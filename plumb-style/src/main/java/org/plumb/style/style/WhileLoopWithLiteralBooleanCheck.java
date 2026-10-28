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

import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** while(true) / for(;;) : use break or extract condition. */
public final class WhileLoopWithLiteralBooleanCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-while-literal-boolean", "WHILE_LITERAL_BOOLEAN"),
          "while(true) / for(;;) : use break or extract condition",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.WhileLoopWithLiteralBooleanCheck",
          true,
          List.of("ast-style-loop-with-literal-boolean", "while-literal-boolean"));

  @Override
  public String name() {
    return "while-literal-boolean";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    if (!(n.getCondition() instanceof BooleanLiteralExpr)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "while(true) loop : verify a break is reachable",
        RuleDef.Severity.LOW,
        "while");
  }

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    if (!n.getInitialization().isEmpty()) return;
    if (n.getCompare().isPresent()) return;
    if (n.getUpdate().isEmpty()) return;
    // for (;;) : empty init, no compare, empty update.
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "for(;;) infinite loop : verify a break is reachable",
        RuleDef.Severity.LOW,
        "for");
  }
}

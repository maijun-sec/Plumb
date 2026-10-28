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

import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** nested block : extract to a method. */
public final class NestedBlockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-nested-block", "NESTED_BLOCK"),
          "nested block : extract to a method",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NestedBlockCheck",
          true,
          List.of("ast-style-nested-block", "nested-block"));

  @Override
  public String name() {
    return "nested-block";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BlockStmt n, StyleContext ctx) {
    // Only fire when the parent is a BlockStmt.
    if (!(n.getParentNode().orElse(null) instanceof BlockStmt)) return;
    // Don't fire on switch block or try/catch.
    if (n.getParentNode().orElse(null) instanceof com.github.javaparser.ast.stmt.SwitchEntry)
      return;
    if (n.getParentNode().orElse(null) instanceof com.github.javaparser.ast.stmt.CatchClause)
      return;
    if (n.getParentNode().orElse(null) instanceof com.github.javaparser.ast.stmt.TryStmt) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line, col, DEFAULT_RULE, "Nested block : extract to a method", RuleDef.Severity.LOW, "{");
  }
}

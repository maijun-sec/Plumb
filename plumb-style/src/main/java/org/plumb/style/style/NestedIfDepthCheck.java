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
/*
 * nested if depth > 2 (heuristic).
 */
package org.plumb.style.style;

import com.github.javaparser.ast.stmt.IfStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class NestedIfDepthCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-nested-if-depth", "NESTED_IF_DEPTH"),
          "nested if depth > 2 (heuristic)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NestedIfDepthCheck",
          true,
          List.of("ast-style-nested-if-depth", "nested-if-depth"));

  @Override
  public String name() {
    return "nested-if-depth";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt f, StyleContext ctx) {
    int depth = 0;
    com.github.javaparser.ast.Node parent = f.getParentNode().orElse(null);
    while (parent != null) {
      if (parent instanceof IfStmt) {
        depth++;
        if (depth > 5) return;
      }
      parent = parent.getParentNode().orElse(null);
    }
    if (depth >= 2) {
      int line = f.getBegin().map(p -> p.line).orElse(-1);
      int col = f.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "Nested if depth > 2; refactor", RuleDef.Severity.LOW, "if");
    }
  }
}

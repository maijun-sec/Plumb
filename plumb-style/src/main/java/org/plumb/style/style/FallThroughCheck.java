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

import com.github.javaparser.ast.stmt.SwitchStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** switch case fallthrough without // fallthrough. */
public final class FallThroughCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-fallthrough", "FALLTHROUGH"),
          "switch case fallthrough without // fallthrough",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.FallThroughCheck",
          true,
          List.of("ast-style-fallthrough", "fallthroughcheck"));

  @Override
  public String name() {
    return "fallthrough";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SwitchStmt s, StyleContext ctx) {
    java.util.List<com.github.javaparser.ast.stmt.SwitchEntry> entries = s.getEntries();
    for (int i = 0; i + 1 < entries.size(); i++) {
      com.github.javaparser.ast.stmt.SwitchEntry e = entries.get(i);
      com.github.javaparser.ast.stmt.SwitchEntry next = entries.get(i + 1);
      if (e.getLabels().isEmpty() || e.getStatements().isEmpty()) return;
      com.github.javaparser.ast.stmt.Statement last =
          e.getStatements().get(e.getStatements().size() - 1);
      boolean hasFallthrough = false;
      if (last instanceof com.github.javaparser.ast.stmt.ReturnStmt) hasFallthrough = true;
      else if (last instanceof com.github.javaparser.ast.stmt.BreakStmt) hasFallthrough = true;
      else if (last instanceof com.github.javaparser.ast.stmt.ContinueStmt) hasFallthrough = true;
      else if (last instanceof com.github.javaparser.ast.stmt.ThrowStmt) hasFallthrough = true;
      if (!hasFallthrough) {
        int line = e.getBegin().map(p -> p.line).orElse(-1);
        int col = e.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Switch case fallthrough without break/return/throw",
            RuleDef.Severity.LOW,
            "case");
      }
    }
  }
}

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

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** label is never used (dead code). */
public final class UnusedLabelCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unused-label", "UNUSED_LABEL"),
          "label is never used (dead code)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnusedLabelCheck",
          true,
          List.of("ast-style-unused-label", "unused-label"));

  @Override
  public String name() {
    return "unused-label";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    Set<String> labels = new HashSet<>();
    n.findAll(LabeledStmt.class).forEach(l -> labels.add(l.getLabel().asString()));
    if (labels.isEmpty()) return;
    Set<String> used = new HashSet<>();
    n.findAll(BreakStmt.class)
        .forEach(b -> b.getLabel().ifPresent(name -> used.add(name.asString())));
    n.findAll(ContinueStmt.class)
        .forEach(c -> c.getLabel().ifPresent(name -> used.add(name.asString())));
    for (LabeledStmt l : n.findAll(LabeledStmt.class)) {
      if (!used.contains(l.getLabel().asString())) {
        int line = l.getBegin().map(p -> p.line).orElse(-1);
        int col = l.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Label '" + l.getLabel().asString() + "' is never used",
            RuleDef.Severity.LOW,
            l.getLabel().asString());
      }
    }
  }
}

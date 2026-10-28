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
 * blank line between methods (strict).
 */
package org.plumb.style.style;

// Note: this checker needs cross-method state (compare each method to the next one),
// so the implementation uses init() to collect the list of methods in document order
// and finish() to fire per-pair reports.

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.ArrayList;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class EmptyLineSeparatorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-emptylineseparatorcheck-cs", "EMPTYLINESEPARATORCHECK_CS"),
          "blank line between methods (strict)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyLineSeparatorCheck",
          true,
          List.of("ast-style-line-separator-strict", "emptylineseparatorcheck_cs"));

  private final List<MethodDeclaration> methods = new ArrayList<>();

  @Override
  public String name() {
    return "emptylineseparatorcheck-cs";
  }

  @Override
  public void init(StyleContext ctx) {
    methods.clear();
    com.github.javaparser.ast.CompilationUnit unit = ctx.source().unit();
    if (unit != null) {
      methods.addAll(unit.findAll(MethodDeclaration.class));
    }
  }

  @Override
  public void finish(StyleContext ctx) {
    for (int i = 0; i + 1 < methods.size(); i++) {
      MethodDeclaration prev = methods.get(i);
      MethodDeclaration next = methods.get(i + 1);
      int endOfPrev = prev.getEnd().map(p -> p.line).orElse(-1);
      int startOfNext = next.getBegin().map(p -> p.line).orElse(-1);
      if (startOfNext - endOfPrev < 2) {
        int line = next.getBegin().map(p -> p.line).orElse(-1);
        int col = next.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Add blank line before method " + next.getNameAsString(),
            RuleDef.Severity.LOW,
            next.getNameAsString());
      }
    }
    methods.clear();
  }
}

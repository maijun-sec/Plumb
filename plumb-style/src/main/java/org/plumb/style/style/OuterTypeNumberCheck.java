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

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** outer type number (only one outer type per file). */
public final class OuterTypeNumberCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-outertypenumber", "OUTERTYPENUMBER"),
          "outer type number (only one outer type per file)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.OuterTypeNumberCheck",
          true,
          List.of("ast-style-type-number", "outertypenumbercheck"));

  @Override
  public String name() {
    return "outertypenumber";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    int topLevel =
        (int)
            n.getTypes().stream()
                .filter(
                    t ->
                        !(t instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)
                            || !((com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) t)
                                .isNestedType())
                .count();
    if (topLevel > 1) {
      int line = 1, col = 1;
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "File has " + topLevel + " top-level types; expected 1",
          RuleDef.Severity.LOW,
          "file");
    }
  }
}

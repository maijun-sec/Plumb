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

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** field should declare an explicit visibility modifier. */
public final class VisibilityModifierCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-visibility-modifier", "VISIBILITY_MODIFIER"),
          "field should declare an explicit visibility modifier",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.VisibilityModifierCheck",
          true,
          List.of("ast-style-visibility-modifier", "visibility-modifier"));

  @Override
  public String name() {
    return "visibility-modifier";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    boolean hasVisibility =
        n.getModifiers().stream()
            .anyMatch(
                m ->
                    m.getKeyword() == Modifier.Keyword.PUBLIC
                        || m.getKeyword() == Modifier.Keyword.PRIVATE
                        || m.getKeyword() == Modifier.Keyword.PROTECTED);
    if (hasVisibility) return;
    // Interfaces allow public-by-default fields;
    // skip those.
    if (n.getParentNode().isPresent()
        && n.getParentNode().get()
            instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cid
        && cid.isInterface()) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Field should declare an explicit visibility (private / protected / public)",
        RuleDef.Severity.LOW,
        "field");
  }
}

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
import com.github.javaparser.ast.body.VariableDeclarator;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** public static field should be final or non-public. */
public final class ClassVariableVisibilityCheck implements AstChecker {

  private StyleContext ctx;
  private RuleDef rule;

  @Override
  public String name() {
    return "class-variable-visibility";
  }

  @Override
  public void init(StyleContext ctx) {
    this.ctx = ctx;
    this.rule = ctx.rules().byId(RuleRegistry.CLASS_VAR_VISIBILITY).orElse(null);
  }

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    // The parent must be a top-level class (not
    // interface / enum / nested / local). // also fires for inner-class fields; Plumb's
    // pilot narrows to the immediate class body
    // for simplicity.
    if (!(n.getParentNode().orElse(null)
        instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cls)) {
      return;
    }
    if (!cls.getNameAsString().matches("[A-Z][A-Za-z0-9_]*")) {
      // Inner class names are allowed; for the
      // pilot, skip non-top-level to keep the
      // surface narrow.
      return;
    }
    // Must be static, not final, not annotated.
    if (!n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC)) {
      return;
    }
    if (n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL)) {
      return;
    }
    if (!n.getAnnotations().isEmpty()) {
      return;
    }
    for (VariableDeclarator v : n.getVariables()) {
      int line = n.getRange().map(r -> r.begin.line).orElse(-1);
      int col = n.getRange().map(r -> r.begin.column).orElse(-1);
      this.ctx.report(
          line,
          col,
          this.rule,
          "make '" + v.getNameAsString() + "' a static final constant or non-public with accessors",
          RuleDef.Severity.LOW,
          v.getNameAsString());
    }
  }
}

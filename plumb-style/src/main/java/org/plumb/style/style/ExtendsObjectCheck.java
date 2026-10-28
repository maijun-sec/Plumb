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

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Class explicitly extends Object, the declaration is redundant. */
public final class ExtendsObjectCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-extends-object", "EXTENDS_OBJECT"),
          "Class explicitly extends Object, the declaration is redundant",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ExtendsObjectCheck",
          true,
          List.of("ast-style-extends-object", "extends-object"));

  @Override
  public String name() {
    return "extends-object";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    // Interfaces don't have an extends Object (they
    // can extend other interfaces). Skip them.
    if (n.isInterface()) return;
    // Walk the extends list and check each name.
    for (ClassOrInterfaceType ext : n.getExtendedTypes()) {
      final String name = ext.getNameAsString();
      final String fqn = ext.getNameWithScope();
      if ("Object".equals(name) || "java.lang.Object".equals(fqn)) {
        ctx.report(
            n.getBegin().map(p -> p.line).orElse(-1),
            n.getBegin().map(p -> p.column).orElse(-1),
            DEFAULT_RULE,
            "Class '" + n.getNameAsString() + "' explicitly extends Object (redundant)",
            RuleDef.Severity.LOW,
            n.getNameAsString());
      }
    }
  }
}

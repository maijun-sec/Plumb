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

/** superclass uses sub-classed generic (IC_SUPERCLASS_GENERIC). */
public final class IcSuperclassGenericCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-ic-superclass-generic", "IC_SUPERCLASS_GENERIC"),
          "superclass uses sub-classed generic (IC_SUPERCLASS_GENERIC)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.IcSuperclassGenericCheck",
          true,
          List.of("ast-style-superclass-generic", "ic-superclass-generic"));

  @Override
  public String name() {
    return "ic-superclass-generic";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    for (ClassOrInterfaceType ext : n.getExtendedTypes()) {
      if (ext.getTypeArguments().isPresent()) continue;
      String name = ext.getNameAsString();
      // Raw generic parent.
      if ("Comparable".equals(name)
          || "Comparator".equals(name)
          || "Iterable".equals(name)
          || "Supplier".equals(name)
          || "Function".equals(name)
          || "Predicate".equals(name)) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Class '"
                + n.getNameAsString()
                + "' extends raw '"
                + name
                + "' : specify the type argument",
            RuleDef.Severity.MEDIUM,
            name);
      }
    }
  }
}

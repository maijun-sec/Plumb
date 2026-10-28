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
package org.plumb.style.design;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** inheritance tree is too deep (, default 5). */
public final class ClassInheritanceDepthCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-inheritance-depth", "INHERITANCE_DEPTH"),
          "inheritance tree is too deep (, default 5)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ClassInheritanceDepthCheck",
          true,
          List.of("ast-design-inheritance-depth", "inheritance-depth"));

  private static final int DEFAULT_MAX = 5;

  @Override
  public String name() {
    return "inheritance-depth";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.getExtendedTypes().isEmpty()) return;
    int depth = computeDepth(n);
    if (depth > DEFAULT_MAX) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Class '"
              + n.getNameAsString()
              + "' has inheritance depth "
              + depth
              + " (limit "
              + DEFAULT_MAX
              + ")",
          RuleDef.Severity.LOW,
          n.getNameAsString());
    }
  }

  /**
   * Without symbol resolution we can't follow the chain exactly, so we use a simple heuristic: the
   * depth of any class is at least 1 + the maximum of its parents' depths. Since we can't resolve
   * parents, we count the number of `extends` hops declared in the file.
   */
  private static int computeDepth(ClassOrInterfaceDeclaration n) {
    int depth = 1;
    for (com.github.javaparser.ast.type.ClassOrInterfaceType ext : n.getExtendedTypes()) {
      String name = ext.getNameAsString();
      ClassOrInterfaceDeclaration parent = findClassInCompilationUnit(n, name);
      if (parent != null) {
        depth = Math.max(depth, 1 + computeDepth(parent));
      } else {
        depth = Math.max(depth, 2);
      }
    }
    return depth;
  }

  private static ClassOrInterfaceDeclaration findClassInCompilationUnit(
      com.github.javaparser.ast.Node from, String name) {
    com.github.javaparser.ast.Node cu = from;
    while (cu.getParentNode().isPresent()) cu = cu.getParentNode().get();
    if (!(cu instanceof com.github.javaparser.ast.CompilationUnit)) return null;
    for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
      if (c.getNameAsString().equals(name) && c != from) {
        return c;
      }
    }
    return null;
  }
}

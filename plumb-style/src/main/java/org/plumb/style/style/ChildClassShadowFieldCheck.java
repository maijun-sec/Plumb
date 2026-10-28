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
 * Subclass field shadows parent field (CWE-849).
 */
package org.plumb.style.style;

// Note: this checker has a documented two-phase pattern (build class-name map in
// visit(CompilationUnit) before per-class visits fire). The map is now built
// in init() (called once per file) and consumed in visit(ClassOrInterfaceDeclaration).

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class ChildClassShadowFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-child-class-shadow-field", "CHILD_CLASS_SHADOW_FIELD"),
          "subclass field shadows parent field",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-849"),
          "org.plumb.style.ChildClassShadowFieldCheck",
          true,
          List.of("ast-style-class-shadow-field", "child-class-shadow-field"));

  @Override
  public String name() {
    return "child-class-shadow-field";
  }

  /** Per-file map: class simple name -> declaration. Populated in init(). */
  private final Map<String, ClassOrInterfaceDeclaration> byName = new HashMap<>();

  @Override
  public void init(StyleContext ctx) {
    byName.clear();
    com.github.javaparser.ast.CompilationUnit unit = ctx.source().unit();
    if (unit != null) {
      for (ClassOrInterfaceDeclaration c : unit.findAll(ClassOrInterfaceDeclaration.class)) {
        byName.put(c.getNameAsString(), c);
      }
    }
  }

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (byName.isEmpty()) return;
    for (ClassOrInterfaceType extendedType : c.getExtendedTypes()) {
      String extendedName = extendedType.getNameAsString();
      ClassOrInterfaceDeclaration parent = byName.get(extendedName);
      if (parent == null) continue;
      Set<String> parentNames = new HashSet<>();
      for (FieldDeclaration f : parent.getFields()) {
        for (VariableDeclarator v : f.getVariables()) {
          parentNames.add(v.getNameAsString());
        }
      }
      for (FieldDeclaration f : c.getFields()) {
        for (VariableDeclarator v : f.getVariables()) {
          if (parentNames.contains(v.getNameAsString())) {
            int line = v.getBegin().map(p -> p.line).orElse(-1);
            int col = v.getBegin().map(p -> p.column).orElse(-1);
            ctx.report(
                line,
                col,
                DEFAULT_RULE,
                "Subclass field " + v.getNameAsString() + " shadows parent field",
                RuleDef.Severity.MEDIUM,
                v.getNameAsString());
          }
        }
      }
    }
  }
}

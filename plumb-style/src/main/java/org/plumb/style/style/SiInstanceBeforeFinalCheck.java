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
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** instance init reads static final before assignment (SI_INSTANCE_BEFORE_FINALS_ASSIGNED). */
public final class SiInstanceBeforeFinalCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-si-instance-before-final", "SI_INSTANCE_BEFORE_FINAL"),
          "instance init reads static final before assignment (SI_INSTANCE_BEFORE_FINALS_ASSIGNED)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-457"),
          "org.plumb.style.SiInstanceBeforeFinalCheck",
          true,
          List.of("ast-style-instance-before-finals-assigned", "si-instance-before-final"));

  @Override
  public String name() {
    return "si-instance-before-final";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    // Detect: a class with a static final field
    // and a constructor / instance initializer.
    // The static field is at risk if the
    // constructor / init reads it.
    for (com.github.javaparser.ast.body.TypeDeclaration<?> td : n.getTypes()) {
      if (!(td instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)) continue;
      com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cid =
          (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) td;
      for (FieldDeclaration f : cid.getFields()) {
        boolean isStatic =
            f.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
        boolean isFinal =
            f.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
        if (!isStatic || !isFinal) continue;
        // The narrow check: there is a constructor.
        if (cid.getConstructors().isEmpty()) continue;
        int line = f.getBegin().map(p -> p.line).orElse(-1);
        int col = f.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "static final field present with a constructor : "
                + "ensure it's assigned before instance reads it",
            RuleDef.Severity.LOW,
            "static-final");
      }
    }
  }
}

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
package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Serializable class field is not marked transient or serialVersionUID. */
public final class BeanMembersShouldSerializeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-bean-members-serialize", "BEAN_MEMBERS_SERIALIZE"),
          "Serializable class field is not marked transient or serialVersionUID",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.BeanMembersShouldSerializeCheck",
          true,
          List.of("ast-naming-members-should-serialize", "bean-members-serialize"));

  @Override
  public String name() {
    return "bean-members-serialize";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    // Check if the class implements Serializable.
    boolean serializable =
        n.getImplementedTypes().stream()
            .anyMatch(
                t ->
                    t.getNameAsString().equals("Serializable")
                        || t.getNameAsString().equals("java.io.Serializable"));
    if (!serializable) return;
    for (FieldDeclaration f : n.getFields()) {
      if (f.isStatic()) continue;
      if (f.isTransient()) continue;
      // Skip serialVersionUID.
      for (var v : f.getVariables()) {
        if (v.getNameAsString().equals("serialVersionUID")) continue;
        int line = v.getBegin().map(p -> p.line).orElse(-1);
        int col = v.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Field '"
                + v.getNameAsString()
                + "' in Serializable class : mark transient or add to serialVersionUID",
            RuleDef.Severity.MEDIUM,
            v.getNameAsString());
      }
    }
  }
}

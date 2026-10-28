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
package org.plumb.style.bug;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** equals() should accept Object, not a subtype. */
public final class CovariantEqualsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-covariant-equals", "COVARIANT_EQUALS"),
          "equals() should accept Object, not a subtype",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-697"),
          "org.plumb.style.CovariantEqualsCheck",
          true,
          List.of("ast-bug-covariant-equals", "covariant-equals"));

  @Override
  public String name() {
    return "covariant-equals";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (!n.getNameAsString().equals("equals")) return;
    if (n.getParameters().size() != 1) return;
    Parameter p = n.getParameters().get(0);
    // Check the parameter type : it must be a class type
    // with name "Object" or "java.lang.Object".
    String typeName = p.getType().asString();
    if (typeName.equals("Object") || typeName.equals("java.lang.Object")) {
      return; // canonical equals
    }
    // If it's a primitive or array, also not a covariant
    // override; only class types with non-Object names are.
    if (!(p.getType() instanceof ClassOrInterfaceType)) return;
    int line = n.getBegin().map(p2 -> p2.line).orElse(-1);
    int col = n.getBegin().map(p2 -> p2.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "equals() should accept 'Object' but is declared with '" + typeName + "'",
        RuleDef.Severity.MEDIUM,
        n.getNameAsString());
  }
}

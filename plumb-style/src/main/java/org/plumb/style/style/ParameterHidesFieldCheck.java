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

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** method parameter shadows a class field. */
public final class ParameterHidesFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-param-hides-field", "PARAM_HIDES_FIELD"),
          "method parameter shadows a class field",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-1006"),
          "org.plumb.style.ParameterHidesFieldCheck",
          true,
          List.of("ast-style-hides-field", "param-hides-field"));

  @Override
  public String name() {
    return "param-hides-field";
  }

  @Override
  public void init(StyleContext ctx) {
    // no per-file state needed
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // Get the enclosing class's field names. We
    // can't easily get to the parent class from a
    // method without walking up, but the dispatcher
    // gives us the method directly. Instead, we
    // check the immediate parent for ClassOrInterface
    // fields. For simplicity and to avoid coupling
    // to AST walking, we just check the method's
    // own parameters and look for shadowing against
    // the same method's local declarations... no,
    // that's not right. We need class-level fields.
    // Skip this for now if we can't get the class.
    // We CAN get the class via n.getParentNode() in
    // JavaParser 3.26.
    if (n.getParentNode().isEmpty()) return;
    if (!(n.getParentNode().get()
        instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cls)) return;
    // Collect field names
    java.util.Set<String> fieldNames = new java.util.HashSet<>();
    for (FieldDeclaration fd : cls.getFields()) {
      for (VariableDeclarator vd : fd.getVariables()) {
        fieldNames.add(vd.getNameAsString());
      }
    }
    if (fieldNames.isEmpty()) return;
    // Check each parameter against field names
    for (Parameter p : n.getParameters()) {
      if (fieldNames.contains(p.getNameAsString())) {
        ctx.report(
            n.getBegin().map(pos -> pos.line).orElse(-1),
            n.getBegin().map(pos -> pos.column).orElse(-1),
            DEFAULT_RULE,
            "Method parameter '" + p.getNameAsString() + "' hides a class field of the same name",
            RuleDef.Severity.MEDIUM,
            p.getNameAsString());
      }
    }
  }
}

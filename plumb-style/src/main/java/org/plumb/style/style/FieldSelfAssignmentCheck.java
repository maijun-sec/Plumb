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
 * field self-assignment (this.x = x).
 *
 * <p><b>pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(ClassOrInterfaceDeclaration)} (1 walk) + for
 * each (class, method) pair
 * {@code m.findAll(AssignExpr)} (1 walk per method). The new
 * implementation uses {@code visit(AssignExpr)} dispatched by
 * {@code AstDispatcher} + a findAncestor lookup to get the
 * enclosing class. Total: 1 walk per file (was 1 + M walks).</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class FieldSelfAssignmentCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-fieldselfassignment", "FIELDSELFASSIGNMENT"),
          "field self-assignment (this.x = x)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.FieldSelfAssignmentCheck",
          true,
          List.of("legacy-self-assignment", "fieldselfassignmentcheck"));

  @Override
  public String name() {
    return "fieldselfassignment";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(AssignExpr ae, StyleContext ctx) {
    if (ae.getOperator() != AssignExpr.Operator.ASSIGN) return;
    if (!(ae.getTarget() instanceof NameExpr)) return;
    if (!(ae.getValue() instanceof NameExpr)) return;
    String targetName = ((NameExpr) ae.getTarget()).getNameAsString();
    String valueName = ((NameExpr) ae.getValue()).getNameAsString();
    if (!targetName.equals(valueName)) return;
    // Find enclosing class.
    ClassOrInterfaceDeclaration c = ae.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
    if (c == null) return;
    Set<String> fieldNames = new HashSet<>();
    for (FieldDeclaration f : c.getFields()) {
      for (VariableDeclarator v : f.getVariables()) {
        fieldNames.add(v.getNameAsString());
      }
    }
    if (!fieldNames.contains(targetName)) return;
    int line = ae.getBegin().map(p -> p.line).orElse(-1);
    int col = ae.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Field self-assignment: " + targetName,
        RuleDef.Severity.LOW,
        targetName);
  }
}

/*
 * field self-assignment (this.x = x).
 *
 * <p><b>R-818 (2026-10-14): pattern D rewrite.</b> The previous
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

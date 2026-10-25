/*
 * non-final field is never reassigned (should be final).
 *
 * <p><b>pattern A rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(ClassOrInterfaceDeclaration)} (1 walk) + for
 * each (class, field) pair
 * {@code c.findAll(AssignExpr)} (1 walk per field). Worst case:
 * 1 N_class·N_field walks. The new implementation does 1 walk
 * to build a per-class Set of assigned-target names, then checks
 * the field-set membership.</p>
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

public final class ImmutableFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-immutablefield", "IMMUTABLEFIELD"),
          "non-final field is never reassigned (should be final)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ImmutableFieldCheck",
          false,
          List.of(
              "ast-style-immutable-field-strict",
              "immutablefieldcheck")); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832

  // Disabled by default: this rule reports a style preference rather than a defect.

  @Override
  public String name() {
    return "immutablefield";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    // Single walk per class to collect the set of
    // target-name strings that are reassigned anywhere
    // in the class body.
    Set<String> reassigned = new HashSet<>();
    c.walk(
        AssignExpr.class,
        ae -> {
          if (ae.getTarget() instanceof NameExpr ne) {
            reassigned.add(ne.getNameAsString());
          }
        });
    for (FieldDeclaration f : c.getFields()) {
      if (f.isFinal() || f.isStatic()) return;
      for (VariableDeclarator v : f.getVariables()) {
        String name = v.getNameAsString();
        if (reassigned.contains(name)) return;
        int line = v.getBegin().map(p -> p.line).orElse(-1);
        int col = v.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Field " + name + " could be final (never reassigned)",
            RuleDef.Severity.LOW,
            name);
      }
    }
  }
}

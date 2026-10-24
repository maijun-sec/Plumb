package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Assignment to a non-final static field.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `ignoreFinalFields` | BOOLEAN | `true` | Skip final fields (immutable by design) |
 */
public final class AssignmentToNonFinalStaticCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "ignoreFinalFields",
        "Skip final fields (immutable by design)",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-assignment-to-non-final-static", "ASSIGNMENT_TO_NON_FINAL_STATIC"),
          "Assignment to a non-final static field",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.AssignmentToNonFinalStaticCheck",
          true,
          List.of("ast-style-assignment-to-non-final-static", "assignment-to-non-final-static"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "assignment-to-non-final-static";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    boolean ignoreFinal = paramBool(ctx, "ignoreFinalFields", true);
    for (AssignExpr assign : n.getBody().get().findAll(AssignExpr.class)) {
      // The LHS can be a plain NameExpr (e.g. `x = 1`) or a
      // qualified FieldAccessExpr (e.g. `N.x = 1`). Extract
      // the field name from either form.
      String fieldName = null;
      if (assign.getTarget() instanceof NameExpr ne) {
        fieldName = ne.getNameAsString();
      } else if (assign.getTarget() instanceof com.github.javaparser.ast.expr.FieldAccessExpr fa) {
        // For `N.x = 1`, the "field" is the right-most name.
        // For `this.x = 1`, that's `x`.
        fieldName = fa.getNameAsString();
      }
      if (fieldName == null) continue;
      for (FieldDeclaration f : enclosingFields(n, fieldName)) {
        if (!f.isStatic()) continue;
        if (ignoreFinal && f.isFinal()) continue;
        if (f.hasModifier(Modifier.Keyword.VOLATILE)) continue; // volatile is a deliberate choice
        int line = assign.getBegin().map(p -> p.line).orElse(-1);
        int col = assign.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Assignment to non-final static field '" + fieldName + "'",
            RuleDef.Severity.MEDIUM,
            fieldName);
      }
    }
  }

  private static List<FieldDeclaration> enclosingFields(MethodDeclaration n, String name) {
    return n.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
        .map(
            c -> {
              java.util.List<FieldDeclaration> out = new java.util.ArrayList<>();
              for (FieldDeclaration f : c.getFields()) {
                for (VariableDeclarator v : f.getVariables()) {
                  if (v.getNameAsString().equals(name)) {
                    out.add(f);
                    break;
                  }
                }
              }
              return out;
            })
        .orElse(java.util.Collections.emptyList());
  }

  private static boolean paramBool(StyleContext ctx, String name, boolean def) {
    Boolean override = ctx.paramOverride(name, Boolean.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.boolDefault();
    }
    return def;
  }
}

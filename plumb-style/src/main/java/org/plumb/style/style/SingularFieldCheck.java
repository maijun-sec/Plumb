package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** field is only used in one method : make it local. */
public final class SingularFieldCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-singular-field", "SINGULAR_FIELD"),
          "field is only used in one method : make it local",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SingularFieldCheck",
          false,
          List.of(
              "ast-style-singular-field",
              "singular-field")); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832

  // Disabled by default: this rule reports a style preference rather than a defect.

  @Override
  public String name() {
    return "singular-field";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    for (FieldDeclaration f : n.getFields()) {
      for (VariableDeclarator v : f.getVariables()) {
        String name = v.getNameAsString();
        Set<String> usedInMethods = new HashSet<>();
        for (MethodDeclaration m :
            n.getMembers().stream()
                .filter(m2 -> m2 instanceof MethodDeclaration)
                .map(m2 -> (MethodDeclaration) m2)
                .toList()) {
          if (m.findAll(NameExpr.class).stream()
              .anyMatch(ne -> ne.getNameAsString().equals(name))) {
            usedInMethods.add(m.getNameAsString());
          }
        }
        if (usedInMethods.size() == 1) {
          int line = v.getBegin().map(p -> p.line).orElse(-1);
          int col = v.getBegin().map(p -> p.column).orElse(-1);
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "Field '" + name + "' is only used in one method",
              RuleDef.Severity.LOW,
              name);
        }
      }
    }
  }
}

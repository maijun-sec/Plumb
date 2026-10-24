package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class field has the same name as the enclosing class. */
public final class ClassFieldShadowingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-class-field-shadow", "CLASS_FIELD_SHADOW"),
          "class field has the same name as the enclosing class",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.ClassFieldShadowingCheck",
          true,
          List.of("ast-style-field-shadow", "class-field-shadow"));

  @Override
  public String name() {
    return "class-field-shadow";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    String className = n.getNameAsString();
    for (FieldDeclaration f : n.getFields()) {
      for (VariableDeclarator v : f.getVariables()) {
        if (v.getNameAsString().equals(className)) {
          int line = v.getBegin().map(p -> p.line).orElse(-1);
          int col = v.getBegin().map(p -> p.column).orElse(-1);
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "Field '" + v.getNameAsString() + "' has the same name as the class",
              RuleDef.Severity.MEDIUM,
              v.getNameAsString());
        }
      }
    }
  }
}

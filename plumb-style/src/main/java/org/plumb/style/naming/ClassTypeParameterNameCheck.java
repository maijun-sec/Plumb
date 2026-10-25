package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.TypeParameter;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Class type parameter name should be uppercase single letter ({@code T}, {@code K}, {@code V}).
 */
public final class ClassTypeParameterNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-class-type-parameter-name", "CLASS_TYPE_PARAMETER_NAME"),
          "class type parameter name should be uppercase single letter (T, K, V)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ClassTypeParameterNameCheck",
          true,
          List.of("ast-naming-type-param", "class-type-parameter-name"));

  @Override
  public String name() {
    return "class-type-parameter-name";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    for (TypeParameter t : c.getTypeParameters()) {
      String name = t.getNameAsString();
      if (name.length() == 1 && Character.isUpperCase(name.charAt(0))) continue;
      int line = t.getBegin().map(p -> p.line).orElse(-1);
      int col = t.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Class type parameter should be uppercase single letter: " + name,
          RuleDef.Severity.LOW,
          name);
    }
  }
}

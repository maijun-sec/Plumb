package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.TypeParameter;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Interface type parameter name should be uppercase single letter.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(ClassOrInterfaceDeclaration.class)} before. Only fires when
 * {@code c.isInterface()} is true (ClassTypeParameterNameCheck handles class declarations).
 */
public final class InterfaceTypeParameterNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-interface-type-parameter-name", "INTERFACE_TYPE_PARAMETER_NAME"),
          "interface type parameter name should be uppercase single letter",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.InterfaceTypeParameterNameCheck",
          true,
          List.of("ast-naming-interface-type-param", "interface-type-parameter-name")
      );

  @Override
  public String name() {
    return "interface-type-parameter-name";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (!c.isInterface()) return;
    for (TypeParameter t : c.getTypeParameters()) {
      String name = t.getNameAsString();
      if (name.length() <= 1) continue;
      int line = t.getBegin().map(p -> p.line).orElse(-1);
      int col = t.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Interface type parameter should be single letter: " + name,
          RuleDef.Severity.LOW,
          name);
    }
  }
}
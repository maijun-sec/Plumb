package org.plumb.style.design;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Abstract class should not have a {@code public} constructor. */
public final class PublicConstructorInAbstractClassCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-public-ctor-abstract", "PUBLIC_CTOR_ABSTRACT"),
          "abstract class should not have public constructor",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.PublicConstructorInAbstractClassCheck",
          true,
          List.of("ast-design-public-ctor-abstract", "public-ctor-abstract"));

  @Override
  public String name() {
    return "public-ctor-abstract";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (!c.isAbstract()) return;
    for (ConstructorDeclaration ctor : c.getConstructors()) {
      if (!ctor.isPublic()) continue;
      int line = ctor.getBegin().map(p -> p.line).orElse(-1);
      int col = ctor.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Abstract class " + c.getNameAsString() + " should not have public constructor",
          RuleDef.Severity.LOW,
          c.getNameAsString());
    }
  }
}

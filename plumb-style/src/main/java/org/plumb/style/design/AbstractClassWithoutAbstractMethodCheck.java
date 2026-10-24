package org.plumb.style.design;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** abstract class has no abstract members. */
public final class AbstractClassWithoutAbstractMethodCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-abstract-no-method", "ABSTRACT_NO_METHOD"),
          "abstract class has no abstract members",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AbstractClassWithoutAbstractMethodCheck",
          true,
          List.of("ast-design-abstract-class-without-abstract-method", "abstract-no-method"));

  @Override
  public String name() {
    return "abstract-no-method";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (!n.isAbstract()) return;
    if (n.isInterface()) return;
    boolean hasAbstract =
        n.getMembers().stream()
            .filter(m -> m instanceof MethodDeclaration)
            .map(m -> (MethodDeclaration) m)
            .anyMatch(MethodDeclaration::isAbstract);
    if (hasAbstract) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Abstract class '"
            + n.getNameAsString()
            + "' has no abstract members : use final or concrete",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}

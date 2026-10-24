package org.plumb.style.naming;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class with only static members should be a utility class. */
public final class UseUtilityClassCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-use-utility-class", "USE_UTILITY_CLASS"),
          "class with only static members should be a utility class",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UseUtilityClassCheck",
          true,
          List.of("ast-naming-utility-class", "use-utility-class"));

  @Override
  public String name() {
    return "use-utility-class";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.isInterface()) return;
    // Check: all non-static methods are zero, and
    // there's at least one static method.
    boolean hasNonStaticMethod = false;
    boolean hasStaticMember = false;
    boolean hasCtor = false;
    for (var m : n.getMembers()) {
      if (m instanceof ConstructorDeclaration) {
        hasCtor = true;
        continue;
      }
      if (m instanceof com.github.javaparser.ast.nodeTypes.NodeWithModifiers) {
        boolean isStatic =
            ((com.github.javaparser.ast.nodeTypes.NodeWithModifiers<?>) m)
                .getModifiers().stream()
                    .anyMatch(mod -> mod.getKeyword() == Modifier.Keyword.STATIC);
        if (isStatic) {
          hasStaticMember = true;
        } else if (m instanceof com.github.javaparser.ast.body.MethodDeclaration) {
          hasNonStaticMethod = true;
        }
      }
    }
    if (hasNonStaticMethod) return;
    if (!hasStaticMember) return;
    if (hasCtor) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class with only static members : add a private constructor",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}

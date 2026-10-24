package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Protected method in final class (should be private). */
public final class AvoidProtectedMethodInFinalClassCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(
              "ast-style-avoid-protected-method-in-final-class",
              "AVOID_PROTECTED_METHOD_IN_FINAL_CLASS"),
          "protected method in final class (should be private)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AvoidProtectedMethodInFinalClassCheck",
          true,
          List.of("ast-style-protected-method-final", "avoid-protected-method-in-final-class"));

  @Override
  public String name() {
    return "avoid-protected-method-in-final-class";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!m.isProtected()) return;
    ClassOrInterfaceDeclaration c = m.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
    if (c == null || !c.isFinal()) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Protected method in final class: " + m.getNameAsString(),
        RuleDef.Severity.LOW,
        m.getNameAsString());
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class overrides equals() but not hashCode(). */
public final class EqualsHashCodeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-equals-hashcode", "EQUALS_HASHCODE"),
          "class overrides equals() but not hashCode()",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-697"),
          "org.plumb.style.EqualsHashCodeCheck",
          true,
          List.of("ast-style-equals-hashcode", "equals-hashcode")
      );

  @Override
  public String name() {
    return "equals-hashcode";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    for (ClassOrInterfaceDeclaration c : n.findAll(ClassOrInterfaceDeclaration.class)) {
      boolean hasEquals = false, hasHashCode = false;
      for (MethodDeclaration m : c.getMethods()) {
        if (m.getNameAsString().equals("equals")
            && m.getParameters().size() == 1
            && m.getParameter(0).getTypeAsString().equals("Object")) {
          hasEquals = true;
        }
        if (m.getNameAsString().equals("hashCode") && m.getParameters().isEmpty()) {
          hasHashCode = true;
        }
      }
      if (hasEquals && !hasHashCode) {
        int line = c.getBegin().map(p -> p.line).orElse(-1);
        int col = c.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Class " + c.getNameAsString() + " overrides equals() but not hashCode()",
            RuleDef.Severity.MEDIUM,
            c.getNameAsString());
      }
    }
  }
}
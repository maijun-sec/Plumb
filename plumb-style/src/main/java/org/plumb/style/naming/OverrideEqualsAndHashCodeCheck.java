package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** override equals AND hashCode together. */
public final class OverrideEqualsAndHashCodeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-equals-hash", "EQUALS_HASH"),
          "override equals AND hashCode together",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-697"),
          "org.plumb.style.OverrideEqualsAndHashCodeCheck",
          true,
          List.of("ast-naming-override-equals-and-hashcode", "equals-hash"));

  @Override
  public String name() {
    return "equals-hash";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.isInterface()) return;
    boolean hasEquals = false;
    boolean hasHash = false;
    for (MethodDeclaration m :
        n.getMembers().stream()
            .filter(m2 -> m2 instanceof MethodDeclaration)
            .map(m2 -> (MethodDeclaration) m2)
            .toList()) {
      if ("equals".equals(m.getNameAsString()) && m.getParameters().size() == 1) {
        hasEquals = true;
      }
      if ("hashCode".equals(m.getNameAsString()) && m.getParameters().isEmpty()) {
        hasHash = true;
      }
    }
    if (hasEquals != hasHash) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Class overrides "
              + (hasEquals ? "equals" : "hashCode")
              + " but not the other : override both",
          RuleDef.Severity.MEDIUM,
          n.getNameAsString());
    }
  }
}

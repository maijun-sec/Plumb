package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Method override missing {@code @Override} annotation. */
public final class MissingOverrideCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-missing-override", "MISSING_OVERRIDE"),
          "method override missing @Override annotation",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MissingOverrideCheck",
          true,
          List.of("ast-naming-missing-override-strict", "missing-override"));

  @Override
  public String name() {
    return "missing-override";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    for (MethodDeclaration m : c.getMethods()) {
      if (m.isPrivate() || m.isStatic()) continue;
      if (m.getAnnotationByName("Override").isPresent()) continue;
      // Heuristic: Object methods that should be @Override.
      String name = m.getNameAsString();
      if (!"toString".equals(name) && !"hashCode".equals(name) && !"equals".equals(name)) continue;
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Method " + name + " should have @Override annotation",
          RuleDef.Severity.LOW,
          name);
    }
  }
}

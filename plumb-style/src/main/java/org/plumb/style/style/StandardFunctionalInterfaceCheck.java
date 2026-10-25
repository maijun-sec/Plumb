package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** custom interface should be a @FunctionalInterface. */
public final class StandardFunctionalInterfaceCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-standard-functional", "STANDARD_FUNCTIONAL"),
          "custom interface should be a @FunctionalInterface",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.StandardFunctionalInterfaceCheck",
          true,
          List.of("ast-style-standard-functional", "standard-functional"));

  @Override
  public String name() {
    return "standard-functional";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (!c.isInterface()) return;
    if (c.getAnnotationByName("FunctionalInterface").isPresent()) return;
    int abstractCount = 0;
    for (MethodDeclaration m : c.getMethods()) {
      if (m.isAbstract()) abstractCount++;
    }
    if (abstractCount == 1) {
      int line = c.getBegin().map(p -> p.line).orElse(-1);
      int col = c.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Single-abstract-method interface should be @FunctionalInterface: " + c.getNameAsString(),
          RuleDef.Severity.LOW,
          c.getNameAsString());
    }
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Class explicitly extends Object, the declaration is redundant. */
public final class ExtendsObjectCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-extends-object", "EXTENDS_OBJECT"),
          "Class explicitly extends Object, the declaration is redundant",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ExtendsObjectCheck",
          true,
          List.of("ast-style-extends-object", "extends-object")
      );

  @Override
  public String name() {
    return "extends-object";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    // Interfaces don't have an extends Object (they
    // can extend other interfaces). Skip them.
    if (n.isInterface()) return;
    // Walk the extends list and check each name.
    for (ClassOrInterfaceType ext : n.getExtendedTypes()) {
      final String name = ext.getNameAsString();
      final String fqn = ext.getNameWithScope();
      if ("Object".equals(name) || "java.lang.Object".equals(fqn)) {
        ctx.report(
            n.getBegin().map(p -> p.line).orElse(-1),
            n.getBegin().map(p -> p.column).orElse(-1),
            DEFAULT_RULE,
            "Class '" + n.getNameAsString() + "' explicitly extends Object (redundant)",
            RuleDef.Severity.LOW,
            n.getNameAsString());
      }
    }
  }
}

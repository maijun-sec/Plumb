package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Class is not in a named package. */
public final class DefaultPackageCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-default-package", "DEFAULT_PACKAGE"),
          "classes should be in a named package",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DefaultPackageCheck",
          true,
          List.of("ast-style-default-package", "style-default-package"));

  @Override
  public String name() {
    return "default-package";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    Node parent = c.getParentNode().orElse(null);
    if (!(parent instanceof CompilationUnit cu)) return;
    if (cu.getPackageDeclaration().isPresent()) return;
    int line = c.getBegin().map(p -> p.line).orElse(-1);
    int col = c.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class " + c.getNameAsString() + " not in a named package",
        RuleDef.Severity.LOW,
        c.getNameAsString());
  }
}

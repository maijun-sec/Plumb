package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class is in the default package. */
public final class PublicStaticFieldAccessedInSubclassCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-default-package-2", "DEFAULT_PACKAGE_2"),
          "class is in the default package",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.PublicStaticFieldAccessedInSubclassCheck",
          true,
          List.of("ast-style-default-package-public-static-field-accessed-in-subclass", "default-package")
      );

  @Override
  public String name() {
    return "default-package";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    // If the class has no parent package declaration,
    // it's in the default package.
    if (n.findCompilationUnit().isEmpty()) return;
    var cu = n.findCompilationUnit().get();
    if (cu.getPackageDeclaration().isPresent()) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class is in the default package : use a named package",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}

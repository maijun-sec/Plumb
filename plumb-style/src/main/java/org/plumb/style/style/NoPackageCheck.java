package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** top-level class has no package declaration. */
public final class NoPackageCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-no-package", "NO_PACKAGE"),
          "top-level class has no package declaration",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NoPackageCheck",
          true,
          List.of("ast-style-package", "no-package"));

  @Override
  public String name() {
    return "no-package";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    // Only top-level classes (parent is
    // CompilationUnit).
    if (n.getParentNode().isPresent()
        && !(n.getParentNode().get() instanceof com.github.javaparser.ast.CompilationUnit)) {
      return;
    }
    n.findCompilationUnit()
        .ifPresent(
            cu -> {
              if (cu.getPackageDeclaration().isPresent()) return;
              int line = n.getBegin().map(p -> p.line).orElse(-1);
              int col = n.getBegin().map(p -> p.column).orElse(-1);
              ctx.report(
                  line,
                  col,
                  DEFAULT_RULE,
                  "Class '" + n.getNameAsString() + "' has no package declaration",
                  RuleDef.Severity.LOW,
                  n.getNameAsString());
            });
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** file should have a package declaration. */
public final class PackageDeclarationCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-package-declaration", "PACKAGE_DECLARATION"),
          "file should have a package declaration",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.PackageDeclarationCheck",
          true,
          List.of("ast-style-package-declaration", "package-declaration")
      );

  @Override
  public String name() {
    return "package-declaration";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    if (n.getPackageDeclaration().isEmpty()) {
      int line = 1;
      int col = 1;
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "File should have a package declaration",
          RuleDef.Severity.LOW,
          "");
    }
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import java.util.List;
import java.util.Optional;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** missing package-info.java. */
public final class PackageInfoCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-package-info", "PACKAGE_INFO"),
          "missing package-info.java",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.PackageInfoCheck",
          false,
          List.of(
              "ast-style-package-info",
              "package-info")); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832

  // baseline)

  @Override
  public String name() {
    return "package-info";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    Optional<PackageDeclaration> pkg = n.getPackageDeclaration();
    if (pkg.isPresent() && n.getTypes().size() > 0) {
      int line = 1;
      int col = 1;
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Consider adding package-info.java for package " + pkg.get().getNameAsString(),
          RuleDef.Severity.LOW,
          pkg.get().getNameAsString());
    }
  }
}

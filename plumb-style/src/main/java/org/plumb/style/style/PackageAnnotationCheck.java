package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** package-info.java should declare a package annotation. */
public final class PackageAnnotationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-package-annotation", "PACKAGE_ANNOTATION"),
          "package-info.java should declare a package annotation",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.PackageAnnotationCheck",
          true,
          List.of("ast-style-package-annotation", "package-annotation")
      );

  @Override
  public String name() {
    return "package-annotation";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    // Only fire on package-info.java (no top-level types).
    boolean isPackageInfo = n.getTypes().isEmpty();
    if (!isPackageInfo) return;
    if (n.getPackageDeclaration().isEmpty()) return;
    // No annotations on the package declaration.
    var pkg = n.getPackageDeclaration().get();
    if (!pkg.getAnnotations().isEmpty()) return;
    int line = pkg.getBegin().map(p -> p.line).orElse(-1);
    int col = pkg.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "package-info.java has no package-level annotation",
        RuleDef.Severity.LOW,
        "package-info");
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** import from same package : remove. */
public final class ImportFromSamePackageCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-import-same-package", "IMPORT_SAME_PACKAGE"),
          "import from same package : remove",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ImportFromSamePackageCheck",
          true,
          List.of("ast-style-import-from-same-package", "import-same-package")
      );

  @Override
  public String name() {
    return "import-same-package";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    if (n.getPackageDeclaration().isEmpty()) return;
    String pkg = n.getPackageDeclaration().get().getNameAsString();
    for (ImportDeclaration imp : n.getImports()) {
      String name = imp.getNameAsString();
      int lastDot = name.lastIndexOf('.');
      if (lastDot < 0) continue;
      String impPkg = name.substring(0, lastDot);
      if (impPkg.equals(pkg)) {
        int line = imp.getBegin().map(p -> p.line).orElse(-1);
        int col = imp.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Import from same package '" + pkg + "'",
            RuleDef.Severity.LOW,
            name);
      }
    }
  }
}

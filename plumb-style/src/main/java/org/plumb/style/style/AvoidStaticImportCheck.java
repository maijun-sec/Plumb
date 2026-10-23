package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid static imports. */
public final class AvoidStaticImportCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-avoid-static-import", "AVOID_STATIC_IMPORT"),
          "avoid static imports",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AvoidStaticImportCheck",
          true,
          List.of("ast-style-static-import", "avoid-static-import")
      );

  @Override
  public String name() {
    return "avoid-static-import";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    for (ImportDeclaration imp : n.getImports()) {
      if (imp.isStatic()) {
        int line = imp.getBegin().map(p -> p.line).orElse(-1);
        int col = imp.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Avoid static imports; use qualified name",
            RuleDef.Severity.LOW,
            imp.getNameAsString());
      }
    }
  }
}

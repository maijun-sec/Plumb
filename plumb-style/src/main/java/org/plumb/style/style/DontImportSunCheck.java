package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Do not import sun.* packages. */
public final class DontImportSunCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-dontimportsun", "DONTIMPORTSUN"),
          "Do not import sun.* packages",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DontImportSunCheck",
          true,
          List.of("ast-style-import-sun", "dontimportsun"));

  @Override
  public String name() {
    return "dontimportsun";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    for (com.github.javaparser.ast.ImportDeclaration imp : n.getImports()) {
      String name = imp.getNameAsString();
      if (name.startsWith("sun.")) {
        int line = imp.getBegin().map(p -> p.line).orElse(-1);
        int col = imp.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line, col, DEFAULT_RULE, "Do not import sun.* packages", RuleDef.Severity.MEDIUM, name);
      }
    }
  }
}

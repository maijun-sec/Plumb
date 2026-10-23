package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** java.lang.* is imported automatically : remove import. */
public final class DontImportJavaLangCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-dont-import-javalang", "DONT_IMPORT_JAVALANG"),
          "java.lang.* is imported automatically : remove import",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DontImportJavaLangCheck",
          true,
          List.of("ast-style-import-java-lang", "dont-import-javalang")
      );

  @Override
  public String name() {
    return "dont-import-javalang";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    for (ImportDeclaration imp : n.getImports()) {
      if (imp.isStatic()) continue;
      if (!imp.getNameAsString().startsWith("java.lang.")) continue;
      // java.lang.annotation.* etc. is fine, but
      // the core classes (String, Object, etc.)
      // are auto-imported.
      String simple = imp.getNameAsString().substring("java.lang.".length());
      if (!simple.contains(".")) {
        int line = imp.getBegin().map(p -> p.line).orElse(-1);
        int col = imp.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "java.lang." + simple + " is imported automatically",
            RuleDef.Severity.LOW,
            simple);
      }
    }
  }
}

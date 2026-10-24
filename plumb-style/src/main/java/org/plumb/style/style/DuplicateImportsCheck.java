package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** duplicate import : remove the second. */
public final class DuplicateImportsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-duplicate-imports", "DUPLICATE_IMPORTS"),
          "duplicate import : remove the second",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DuplicateImportsCheck",
          true,
          List.of("ast-style-duplicate-imports", "duplicate-imports"));

  @Override
  public String name() {
    return "duplicate-imports";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    Set<String> seen = new HashSet<>();
    for (ImportDeclaration imp : n.getImports()) {
      String key = imp.getNameAsString() + (imp.isStatic() ? "#static" : "");
      if (!seen.add(key)) {
        int line = imp.getBegin().map(p -> p.line).orElse(-1);
        int col = imp.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Duplicate import '" + imp.getNameAsString() + "'",
            RuleDef.Severity.LOW,
            imp.getNameAsString());
      }
    }
  }
}

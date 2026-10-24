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

/** Import is redundant (duplicates an earlier import). */
public final class RedundantImportCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-redundant-import", "REDUNDANT_IMPORT"),
          "Import is redundant (duplicates an earlier import)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RedundantImportCheck",
          true,
          List.of("ast-style-redundant-import", "redundant-import"));

  @Override
  public String name() {
    return "redundant-import";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    List<ImportDeclaration> imports = n.getImports();
    if (imports.size() < 2) return;
    Set<String> seen = new HashSet<>();
    for (ImportDeclaration id : imports) {
      // `x.*` imports are not redundant against `x.Y` :
      // they refer to a different set. Compare asterisks
      // together and explicit imports together.
      String key = (id.isAsterisk() ? "*" : "") + "::" + id.getNameAsString();
      if (!seen.add(key)) {
        int line = id.getBegin().map(p -> p.line).orElse(-1);
        int col = id.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Import '"
                + id.getNameAsString()
                + (id.isAsterisk() ? ".*" : "")
                + "' is redundant : duplicates an earlier import",
            RuleDef.Severity.LOW,
            key);
      }
    }
  }
}

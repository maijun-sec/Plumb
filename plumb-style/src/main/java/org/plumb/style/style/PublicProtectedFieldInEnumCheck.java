package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.body.EnumDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** enum field should be private. */
public final class PublicProtectedFieldInEnumCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-publicprotectedfieldinenum", "PUBLICPROTECTEDFIELDINENUM"),
          "enum field should be private",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.PublicProtectedFieldInEnumCheck",
          true,
          List.of("ast-style-public-field-enum", "publicprotectedfieldinenumcheck"));

  @Override
  public String name() {
    return "publicprotectedfieldinenum";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(EnumDeclaration e, StyleContext ctx) {
    for (com.github.javaparser.ast.body.FieldDeclaration f : e.getFields()) {
      if (f.isPublic() || f.isProtected()) {
        int line = f.getBegin().map(p -> p.line).orElse(-1);
        int col = f.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Enum field should be private: " + f.getVariable(0).getNameAsString(),
            RuleDef.Severity.LOW,
            f.getVariable(0).getNameAsString());
      }
    }
  }
}

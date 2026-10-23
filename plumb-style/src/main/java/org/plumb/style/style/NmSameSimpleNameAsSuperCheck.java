package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class shares a simple name with its superclass (NM_SAME_SIMPLE_NAME_AS_SUPERCLASS). */
public final class NmSameSimpleNameAsSuperCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-nm-same-name-super", "NM_SAME_NAME_SUPER"),
          "class shares a simple name with its superclass (NM_SAME_SIMPLE_NAME_AS_SUPERCLASS)",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.NmSameSimpleNameAsSuperCheck",
          true,
          List.of("ast-style-same-simple-name-as-superclass", "nm-same-name-super")
      );

  @Override
  public String name() {
    return "nm-same-name-super";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration cid, StyleContext ctx) {
    String myName = cid.getNameAsString();
    for (ClassOrInterfaceType ext : cid.getExtendedTypes()) {
      if (ext.getNameAsString().equals(myName) && !ext.getNameWithScope().equals(myName)) {
        int line = cid.getBegin().map(p -> p.line).orElse(-1);
        int col = cid.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Class '" + myName + "' shares its simple name with its superclass",
            RuleDef.Severity.LOW,
            myName);
      }
    }
  }
}

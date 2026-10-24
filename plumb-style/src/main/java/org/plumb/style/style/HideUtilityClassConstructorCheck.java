package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** utility class should hide public constructor. */
public final class HideUtilityClassConstructorCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-hide-utility-class-ctor", "HIDE_UTILITY_CLASS_CTOR"),
          "utility class should hide public constructor",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.HideUtilityClassConstructorCheck",
          true,
          List.of("ast-style-utility-class-ctor", "hide-utility-class-ctor"));

  @Override
  public String name() {
    return "hide-utility-class-ctor";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    boolean allStatic = false;
    for (MethodDeclaration m : c.getMethods()) {
      if (m.isStatic()) {
        allStatic = true;
        break;
      }
    }
    if (!allStatic) return;
    for (ConstructorDeclaration ctor : c.getConstructors()) {
      if (ctor.isPublic()) {
        int line = ctor.getBegin().map(p -> p.line).orElse(-1);
        int col = ctor.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Utility class " + c.getNameAsString() + " should not have public constructor",
            RuleDef.Severity.LOW,
            c.getNameAsString());
      }
    }
  }
}

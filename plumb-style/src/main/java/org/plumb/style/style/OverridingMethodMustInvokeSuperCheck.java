package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Overriding method must invoke super. */
public final class OverridingMethodMustInvokeSuperCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(
              "ast-style-overridingmethodmustinvokesuper-sb", "OVERRIDINGMETHODMUSTINVOKESUPER_SB"),
          "Overriding method must invoke super",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.OverridingMethodMustInvokeSuperCheck",
          true,
          List.of("ast-style-overriding-must-invoke-super", "overridingmethodmustinvokesuper_sb"));

  @Override
  public String name() {
    return "overridingmethodmustinvokesuper-sb";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    String s = m.getNameAsString();
    if (s.equals("hashCode") || s.equals("equals") || s.equals("toString")) {
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Overriding method should invoke super",
          RuleDef.Severity.MEDIUM,
          s);
    }
  }
}

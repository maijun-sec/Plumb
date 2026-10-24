package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Static method need not be synchronized. */
public final class StaticMethodNeedNotBeSynchronizedCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(
              "ast-style-staticmethodneednotbesynchronized-sb",
              "STATICMETHODNEEDNOTBESYNCHRONIZED_SB"),
          "Static method need not be synchronized",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.StaticMethodNeedNotBeSynchronizedCheck",
          true,
          List.of("ast-style-static-method-need-not-sync", "staticmethodneednotbesynchronized_sb"));

  @Override
  public String name() {
    return "staticmethodneednotbesynchronized-sb";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (m.isStatic() && m.isSynchronized()) {
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Static method need not be synchronized",
          RuleDef.Severity.LOW,
          m.getNameAsString());
    }
  }
}

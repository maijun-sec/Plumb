package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** do not instantiate just to call getClass(). */
public final class InstantiationToGetClassCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-instantiationtogetclass", "INSTANTIATIONTOGETCLASS"),
          "do not instantiate just to call getClass()",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.InstantiationToGetClassCheck",
          true,
          List.of("ast-style-instantiation-to-class", "instantiationtogetclasscheck")
      );

  @Override
  public String name() {
    return "instantiationtogetclass";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr oc, StyleContext ctx) {
    if (oc.getParentNode().isPresent()
        && oc.getParentNode().get() instanceof com.github.javaparser.ast.expr.MethodCallExpr
        && "getClass"
            .equals(
                ((com.github.javaparser.ast.expr.MethodCallExpr) oc.getParentNode().get())
                    .getNameAsString())) {
      int line = oc.getBegin().map(p -> p.line).orElse(-1);
      int col = oc.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Use.getClass() on existing instance, not new one",
          RuleDef.Severity.LOW,
          oc.toString());
    }
  }
}

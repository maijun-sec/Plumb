package org.plumb.style.performance;

import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Unnecessary primitive boxing ({@code new Integer(5)} etc.) ? prefer autoboxing or {@code
 * Integer.valueOf}.
 *
 * <p>M5 part-3 migration (2026-10-17): per-node-type callback; was {@code visit(CompilationUnit)
 * + findAll(ObjectCreationExpr.class)} before.
 */
public final class UnnecessaryBoxingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-unnecessary-boxing", "UNNECESSARY_BOXING"),
          "unnecessary primitive boxing (e.g. new Integer(5))",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnnecessaryBoxingCheck",
          true,
          List.of("ast-performance-unnecessary-boxing", "unnecessary-boxing")
      );

  @Override
  public String name() {
    return "unnecessary-boxing";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr oc, StyleContext ctx) {
    if (!(oc.getType() instanceof ClassOrInterfaceType)) return;
    ClassOrInterfaceType t = (ClassOrInterfaceType) oc.getType();
    String name = t.getNameAsString();
    if (!"Integer".equals(name)
        && !"Long".equals(name)
        && !"Boolean".equals(name)
        && !"Double".equals(name)
        && !"Float".equals(name)) return;
    if (oc.getArguments().size() != 1) return;
    if (!(oc.getArgument(0) instanceof IntegerLiteralExpr)) return;
    int line = oc.getBegin().map(p -> p.line).orElse(-1);
    int col = oc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Unnecessary boxing: prefer auto-boxing or " + name + ".valueOf",
        RuleDef.Severity.LOW,
        name);
  }
}
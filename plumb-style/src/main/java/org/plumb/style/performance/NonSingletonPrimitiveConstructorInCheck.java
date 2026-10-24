package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * {@code new Boolean/Integer/Long(primitive)} ? use {@code valueOf} instead.
 *
 * <p>M5 part-3 migration (2026-10-17): per-node-type callback; was {@code visit(CompilationUnit) +
 * findAll(ObjectCreationExpr.class)} before.
 */
public final class NonSingletonPrimitiveConstructorInCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(
              "ast-performance-non-singleton-primitive-constructor",
              "NON_SINGLETON_PRIMITIVE_CONSTRUCTOR"),
          "Boolean constructor unnecessary (use Boolean.valueOf)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.NonSingletonPrimitiveConstructorInCheck",
          true,
          List.of("ast-performance-singleton-primitive", "non-singleton-primitive-constructor"));

  @Override
  public String name() {
    return "non-singleton-primitive-constructor";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr oc, StyleContext ctx) {
    if (!(oc.getType() instanceof ClassOrInterfaceType)) return;
    ClassOrInterfaceType t = (ClassOrInterfaceType) oc.getType();
    String name = t.getNameAsString();
    if (!"Boolean".equals(name) && !"Integer".equals(name) && !"Long".equals(name)) return;
    int line = oc.getBegin().map(p -> p.line).orElse(-1);
    int col = oc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use " + name + ".valueOf(primitive) instead of new " + name + "(primitive)",
        RuleDef.Severity.LOW,
        name);
  }
}

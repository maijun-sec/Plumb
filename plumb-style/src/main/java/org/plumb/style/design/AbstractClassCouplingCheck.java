package org.plumb.style.design;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Too many distinct types instantiated in this class.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `max` | INT
 * | `20` | Maximum distinct instantiable types per class | | `excludeLocalVariables` | BOOLEAN |
 * `false` | Only count types stored in fields |
 */
// R-AST-FIX-2026-10-18 (Pass B): removed duplicate paramInt helper.
public final class AbstractClassCouplingCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "max", "Maximum distinct instantiable types per class", RuleParameter.Type.INT, "20"),
    new RuleParameter(
        "excludeLocalVariables",
        "Only count types stored in fields",
        RuleParameter.Type.BOOLEAN,
        "false"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-abstract-coupling", "ABSTRACT_COUPLING"),
          "Too many distinct types instantiated in this class",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AbstractClassCouplingCheck",
          true,
          List.of("ast-design-abstract-coupling", "abstract-coupling"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "abstract-coupling";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.isNestedType()) return; // don't fire on inner classes
    Integer _o = ctx.paramOverride("max", Integer.class);
    int max = _o != null ? _o : 20;
    Set<String> types = new HashSet<>();
    for (ObjectCreationExpr oc : n.findAll(ObjectCreationExpr.class)) {
      // Skip anonymous classes (they're covered by AnonInnerLengthCheck)
      if (oc.getAnonymousClassBody().isPresent()) continue;
      String typeName = oc.getType().getNameAsString();
      // Skip `new T[0]` (ArrayCreationExpr is a different node,
      // so this won't hit, but be defensive).
      if (typeName.isEmpty()) continue;
      types.add(typeName);
    }
    if (types.size() <= max) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class '"
            + n.getNameAsString()
            + "' instantiates "
            + types.size()
            + " distinct types (max "
            + max
            + ")",
        RuleDef.Severity.LOW,
        "coupling");
  }
}

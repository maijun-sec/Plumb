package org.plumb.style.style;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Use diamond {@code <>} operator instead of explicit type. */
public final class DiamondOperatorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-diamond-operator", "DIAMOND_OPERATOR"),
          "use diamond <> operator instead of explicit type",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DiamondOperatorCheck",
          true,
          List.of("ast-style-diamond-operator", "diamond-operator"));

  @Override
  public String name() {
    return "diamond-operator";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr oc, StyleContext ctx) {
    if (!(oc.getType() instanceof ClassOrInterfaceType)) return;
    ClassOrInterfaceType t = (ClassOrInterfaceType) oc.getType();
    if (t.getTypeArguments().isEmpty()) return;
    int line = oc.getBegin().map(p -> p.line).orElse(-1);
    int col = oc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use diamond <> operator instead of explicit type " + t,
        RuleDef.Severity.LOW,
        t.toString());
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use <> (diamond) operator for generic type inference. */
public final class UseDiamondOperatorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-use-diamond", "USE_DIAMOND"),
          "use <> (diamond) operator for generic type inference",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UseDiamondOperatorCheck",
          true,
          List.of("ast-style-diamond-operator-use", "use-diamond"));

  @Override
  public String name() {
    return "use-diamond";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    if (n.getType() instanceof ClassOrInterfaceType) {
      ClassOrInterfaceType t = (ClassOrInterfaceType) n.getType();
      if (t.getTypeArguments().isPresent() && !t.getTypeArguments().get().isEmpty()) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Use <> (diamond) operator : type can be inferred",
            RuleDef.Severity.LOW,
            "new");
      }
    }
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** {@code new Random(N)} seeded with a predictable constant. */
public final class DmiRandomCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-dmi-random", "DMI_RANDOM"),
          "java.util.Random seeded with predictable value",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.DmiRandomCheck",
          true,
          List.of("ast-style-random", "dmi-random"));

  @Override
  public String name() {
    return "dmi-random";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr oc, StyleContext ctx) {
    if (!(oc.getType() instanceof ClassOrInterfaceType)) return;
    ClassOrInterfaceType t = (ClassOrInterfaceType) oc.getType();
    if (!"Random".equals(t.getNameAsString())) return;
    if (oc.getArguments().size() != 1) return;
    if (!(oc.getArgument(0) instanceof IntegerLiteralExpr lit)) return;
    int v = lit.asNumber().intValue();
    if (v == 0 || v == 1) return;
    int line = oc.getBegin().map(p -> p.line).orElse(-1);
    int col = oc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Random seeded with constant " + v + " (predictable)",
        RuleDef.Severity.MEDIUM,
        "Random");
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** void method with empty body (DMI_USELESS_METHOD). */
public final class DmiUselessMethodCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-dmi-useless-method", "DMI_USELESS_METHOD"),
          "void method with empty body (DMI_USELESS_METHOD)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DmiUselessMethodCheck",
          true,
          List.of("ast-style-useless-method", "dmi-useless-method"));

  @Override
  public String name() {
    return "dmi-useless-method";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    if (!n.getType().isVoidType()) return;
    if (!n.getBody().get().getStatements().isEmpty()) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "void method '" + n.getNameAsString() + "' has empty body",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}

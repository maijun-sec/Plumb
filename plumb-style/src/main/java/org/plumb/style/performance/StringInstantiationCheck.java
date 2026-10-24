package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid new String("...") : string literals are interned. */
public final class StringInstantiationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-string-instantiation", "STRING_INSTANTIATION"),
          "avoid new String(\"...\") : string literals are interned",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.StringInstantiationCheck",
          true,
          List.of("ast-performance-string-instantiation", "string-instantiation"));

  @Override
  public String name() {
    return "string-instantiation";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    if (!"String".equals(n.getType().getNameAsString())) return;
    if (n.getArguments().isEmpty()) return;
    var arg = n.getArgument(0);
    if (arg instanceof com.github.javaparser.ast.expr.StringLiteralExpr) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Avoid new String(\"...\") : string literals are interned",
          RuleDef.Severity.LOW,
          "String");
    }
  }
}

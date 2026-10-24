package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** method should have only one return (strict variant). */
public final class OnlyOneReturnPerMethodCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-only-one-return-strict", "ONLY_ONE_RETURN_STRICT"),
          "method should have only one return (strict variant)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.OnlyOneReturnPerMethodCheck",
          true,
          List.of("ast-style-one-return-strict", "only-one-return-strict"));

  @Override
  public String name() {
    return "only-one-return-strict";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    var returns = n.findAll(ReturnStmt.class);
    if (returns.size() <= 1) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Method has " + returns.size() + " return statements",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}

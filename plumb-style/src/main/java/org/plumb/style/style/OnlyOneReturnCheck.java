package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** method should have only one return statement. */
public final class OnlyOneReturnCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-only-one-return", "ONLY_ONE_RETURN"),
          "method should have only one return statement",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.OnlyOneReturnCheck",
          true,
          List.of("ast-style-one-return", "only-one-return"));

  @Override
  public String name() {
    return "only-one-return";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    var returns = n.findAll(ReturnStmt.class);
    if (returns.size() <= 1) return;
    // Skip lambdas / inner methods : flag the outer
    // one only.
    if (n.findAncestor(MethodDeclaration.class).isPresent()) return;
    for (int i = 0; i < returns.size() - 1; i++) {
      int line = returns.get(i).getBegin().map(p -> p.line).orElse(-1);
      int col = returns.get(i).getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Method has " + returns.size() + " return statements : prefer a single exit",
          RuleDef.Severity.LOW,
          n.getNameAsString());
    }
  }
}

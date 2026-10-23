package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** redundant method : just delegates. */
public final class RedundantMethodCallCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-redundant-method", "REDUNDANT_METHOD"),
          "redundant method : just delegates",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RedundantMethodCallCheck",
          true,
          List.of("ast-style-redundant-method-call", "redundant-method")
      );

  @Override
  public String name() {
    return "redundant-method";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    var stmts = n.getBody().get().getStatements();
    if (stmts.size() != 1) return;
    if (!(stmts.get(0) instanceof com.github.javaparser.ast.stmt.ReturnStmt r)) return;
    if (r.getExpression().isEmpty()) return;
    if (!(r.getExpression().get() instanceof MethodCallExpr m)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Method just delegates to " + m.getNameAsString() + " : consider removing",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}

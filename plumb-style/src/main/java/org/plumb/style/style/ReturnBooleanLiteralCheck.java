package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** method always returns true/false : replace with constant. */
public final class ReturnBooleanLiteralCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-return-bool", "RETURN_BOOL"),
          "method always returns true/false : replace with constant",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ReturnBooleanLiteralCheck",
          false,
          List.of(
              "ast-style-return-boolean-literal",
              "return-bool")); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832

  // baseline)

  @Override
  public String name() {
    return "return-bool";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ReturnStmt n, StyleContext ctx) {
    if (n.getExpression().isEmpty()) return;
    if (!(n.getExpression().get() instanceof BooleanLiteralExpr)) return;
    if (n.findAncestor(MethodDeclaration.class).isEmpty()) return;
    MethodDeclaration md = n.findAncestor(MethodDeclaration.class).get();
    // Verify the method only has one return statement.
    if (md.getBody().isEmpty()) return;
    long returns = md.findAll(ReturnStmt.class).size();
    if (returns != 1) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Method always returns "
            + ((BooleanLiteralExpr) n.getExpression().get()).getValue()
            + " : replace with constant",
        RuleDef.Severity.LOW,
        md.getNameAsString());
  }
}

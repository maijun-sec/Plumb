package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** relational operator asymmetry. */
public final class RelationalOperatorSymmetryCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-relational-symmetry", "RELATIONAL_SYMMETRY"),
          "relational operator asymmetry",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RelationalOperatorSymmetryCheck",
          true,
          List.of("ast-style-relational-symmetry", "relational-symmetry"));

  @Override
  public String name() {
    return "relational-symmetry";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr be, StyleContext ctx) {
    if (be.getOperator() != com.github.javaparser.ast.expr.BinaryExpr.Operator.AND
        && be.getOperator() != com.github.javaparser.ast.expr.BinaryExpr.Operator.OR) {
      return;
    }
    if (!(be.getLeft() instanceof com.github.javaparser.ast.expr.BinaryExpr
        && be.getRight() instanceof com.github.javaparser.ast.expr.BinaryExpr)) {
      return;
    }
    com.github.javaparser.ast.expr.BinaryExpr l =
        (com.github.javaparser.ast.expr.BinaryExpr) be.getLeft();
    com.github.javaparser.ast.expr.BinaryExpr r =
        (com.github.javaparser.ast.expr.BinaryExpr) be.getRight();
    // l = (X op Y), r = (Y op X) with same op or with greater/less swapped
    String ls = l.getLeft().toString() + l.getRight().toString();
    String rs = r.getLeft().toString() + r.getRight().toString();
    if (ls.equals(rs)) {
      int line = be.getBegin().map(p -> p.line).orElse(-1);
      int col = be.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Both sides of " + be.getOperator() + " compare same pair: " + l + " and " + r,
          RuleDef.Severity.LOW,
          be.toString());
    }
  }
}

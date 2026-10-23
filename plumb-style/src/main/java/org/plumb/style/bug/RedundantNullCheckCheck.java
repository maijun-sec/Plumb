package org.plumb.style.bug;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import java.util.List;
import java.util.Optional;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** redundant null check : variable is already known non-null (RCN_REDUNDANT_NULLCHECK). */
public final class RedundantNullCheckCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-redundant-nullcheck", "REDUNDANT_NULLCHECK"),
          "redundant null check : variable is already known non-null (RCN_REDUNDANT_NULLCHECK)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RedundantNullCheckCheck",
          true,
          List.of("ast-bug-redundant-nullcheck", "redundant-nullcheck")
      );

  @Override
  public String name() {
    return "redundant-nullcheck";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    if (!isNullCompare(n.getCondition())) return;
    Expression var = nullCompareTarget(n.getCondition());
    if (var == null) return;
    // The guard's then-stmt must return/throw (so the
    // null case terminates the method).
    if (!terminatesMethod(n.getThenStmt())) return;
    // Look for another `var != null` or `var == null`
    // check AFTER this IfStmt, in the same block.
    Optional<com.github.javaparser.ast.Node> parent = n.getParentNode();
    if (parent.isEmpty()) return;
    String varName = exprName(var);
    if (varName == null) return;
    for (com.github.javaparser.ast.Node sib : parent.get().getChildNodes()) {
      if (sib == n) return; // only after the guard
      if (!(sib instanceof IfStmt sibIf)) continue;
      if (!isNullCompare(sibIf.getCondition())) continue;
      Expression sibVar = nullCompareTarget(sibIf.getCondition());
      if (sibVar == null) continue;
      if (varName.equals(exprName(sibVar))) {
        int line = sibIf.getBegin().map(p -> p.line).orElse(-1);
        int col = sibIf.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Redundant null check on '" + varName + "' : already known to be non-null",
            RuleDef.Severity.LOW,
            varName);
        return;
      }
    }
  }

  private static boolean isNullCompare(Expression e) {
    if (!(e instanceof BinaryExpr be)) return false;
    if (be.getOperator() != BinaryExpr.Operator.EQUALS
        && be.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
      return false;
    }
    return be.getLeft() instanceof NullLiteralExpr || be.getRight() instanceof NullLiteralExpr;
  }

  private static Expression nullCompareTarget(Expression e) {
    if (!(e instanceof BinaryExpr be)) return null;
    if (be.getLeft() instanceof NullLiteralExpr) return be.getRight();
    if (be.getRight() instanceof NullLiteralExpr) return be.getLeft();
    return null;
  }

  private static boolean terminatesMethod(Statement s) {
    if (s == null) return false;
    if (s instanceof ReturnStmt) return true;
    if (s instanceof ThrowStmt) return true;
    if (s instanceof com.github.javaparser.ast.stmt.BlockStmt b) {
      for (Statement child : b.getStatements()) {
        if (terminatesMethod(child)) return true;
      }
    }
    return false;
  }

  private static String exprName(Expression e) {
    if (e instanceof com.github.javaparser.ast.expr.NameExpr ne) {
      return ne.getNameAsString();
    }
    if (e instanceof com.github.javaparser.ast.expr.FieldAccessExpr fa) {
      return fa.getNameAsString();
    }
    return null;
  }
}

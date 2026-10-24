package org.plumb.style.style;

import com.github.javaparser.ast.expr.*;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** cast without matching instanceof check (BC_UNCONFIRMED_CAST). */
public final class UnconfirmedCastCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unconfirmed-cast", "UNCONFIRMED_CAST"),
          "cast without matching instanceof check (BC_UNCONFIRMED_CAST)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-704"),
          "org.plumb.style.UnconfirmedCastCheck",
          true,
          List.of("ast-style-unconfirmed-cast", "unconfirmed-cast"));

  @Override
  public String name() {
    return "unconfirmed-cast";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CastExpr n, StyleContext ctx) {
    String target = n.getType().asString();
    if (isPrimitivelyTyped(target)) return;
    if (looksAlreadyConfirmed(n)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Cast to '" + target + "' is not preceded by an instanceof check",
        RuleDef.Severity.MEDIUM,
        target);
  }

  private static boolean isPrimitivelyTyped(String s) {
    switch (s) {
      case "int":
      case "long":
      case "double":
      case "float":
      case "boolean":
      case "char":
      case "byte":
      case "short":
        return true;
      default:
        return false;
    }
  }

  private static boolean looksAlreadyConfirmed(CastExpr cast) {
    // The parent must be a statement. Walk up to it.
    if (!cast.getParentNode().isPresent()) return false;
    com.github.javaparser.ast.Node parent = cast.getParentNode().get();
    // If parent is an IfStmt's condition, that's already a guard.
    if (parent instanceof com.github.javaparser.ast.stmt.IfStmt) return true;
    // The cast's operand is the expression on the right of the cast.
    Expression operand = cast.getExpression();
    // Look for a `obj instanceof Target` whose `obj` matches
    // the cast's operand by name.
    String operandName = exprName(operand);
    if (operandName == null) return false;
    String target = cast.getType().asString();
    // Walk up the AST to find the enclosing BlockStmt
    // (which is the host of statements we want to scan as
    // siblings of the cast's parent).
    com.github.javaparser.ast.Node container = parent;
    while (container != null && !(container instanceof com.github.javaparser.ast.stmt.BlockStmt)) {
      container = container.getParentNode().orElse(null);
    }
    if (container == null) return false;
    for (com.github.javaparser.ast.Node child : container.getChildNodes()) {
      if (child == parent) return false; // not yet seen
      if (!(child instanceof com.github.javaparser.ast.stmt.IfStmt)) continue;
      com.github.javaparser.ast.stmt.IfStmt ifs = (com.github.javaparser.ast.stmt.IfStmt) child;
      Expression cond = ifs.getCondition();
      while (cond instanceof com.github.javaparser.ast.expr.EnclosedExpr ee) {
        cond = ee.getInner();
      }
      if (cond instanceof com.github.javaparser.ast.expr.InstanceOfExpr ioe) {
        String left = exprName(ioe.getExpression());
        String right = ioe.getType().asString();
        if (operandName.equals(left) && target.equals(right)) {
          return true;
        }
      }
    }
    return false;
  }

  private static String exprName(Expression e) {
    if (e instanceof NameExpr ne) return ne.getNameAsString();
    if (e instanceof MethodCallExpr mc) return mc.getNameAsString();
    if (e instanceof FieldAccessExpr fa) return fa.getNameAsString();
    return null;
  }
}

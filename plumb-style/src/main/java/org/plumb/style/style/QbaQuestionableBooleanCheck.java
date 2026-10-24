package org.plumb.style.style;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** questionable boolean assignment (QBA_QUESTIONABLE_BOOLEAN_ASSIGNMENT). */
public final class QbaQuestionableBooleanCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-qba-questionable-boolean", "QBA_QUESTIONABLE_BOOLEAN"),
          "questionable boolean assignment (QBA_QUESTIONABLE_BOOLEAN_ASSIGNMENT)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-697"),
          "org.plumb.style.QbaQuestionableBooleanCheck",
          true,
          List.of("ast-style-questionable-boolean-assignment", "qba-questionable-boolean"));

  @Override
  public String name() {
    return "qba-questionable-boolean";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(AssignExpr n, StyleContext ctx) {
    if (n.getOperator() != AssignExpr.Operator.ASSIGN) return;
    // The RHS must be a binary comparison or a
    // conditional; the LHS must be a NameExpr
    // (we can't know its type without resolution,
    // so we narrow to a heuristic).
    if (!(n.getValue() instanceof BinaryExpr)) return;
    if (!(n.getTarget() instanceof com.github.javaparser.ast.expr.NameExpr)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Boolean comparison assigned : verify the variable type is boolean",
        RuleDef.Severity.MEDIUM,
        "=");
  }

  @Override
  public void visit(com.github.javaparser.ast.stmt.ExpressionStmt n, StyleContext ctx) {
    if (!(n.getExpression() instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr))
      return;
    com.github.javaparser.ast.expr.VariableDeclarationExpr vde =
        (com.github.javaparser.ast.expr.VariableDeclarationExpr) n.getExpression();
    for (com.github.javaparser.ast.body.VariableDeclarator v : vde.getVariables()) {
      if (!v.getInitializer().isPresent()) continue;
      com.github.javaparser.ast.expr.Expression init = v.getInitializer().get();
      // Unwrap any parens.
      while (init instanceof com.github.javaparser.ast.expr.EnclosedExpr ee) {
        init = ee.getInner();
      }
      if (!(init instanceof BinaryExpr)) continue;
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Boolean comparison assigned : verify the variable type is boolean",
          RuleDef.Severity.MEDIUM,
          "=");
    }
  }
}

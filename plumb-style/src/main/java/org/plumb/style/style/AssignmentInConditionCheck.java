/*
 * R726-C2: AST-style CWE-481 (Assigning Instead of Comparing) checker.
 *
 * <p>Fires when a single {@code =} (assignment) appears
 * in a position the developer likely meant to use
 * {@code ==} (equality): specifically, in an {@code if},
 * {@code while}, or {@code do-while} condition where the
 * assigned target is a variable (not a literal).</p>
 *
 * <p>This catches the classic "if (x = 5)" bug. We don't
 * fire when the LHS is itself a boolean literal (those
 * are intentional and the user typically wants the
 * assignment).</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** CWE-481: Assigning Instead of Comparing. */
public final class AssignmentInConditionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-assignment-in-condition", "ASSIGNMENT_IN_CONDITION"),
          "Assignment in boolean context (likely meant to be comparison) (CWE-481)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-481"),
          "org.plumb.style.style.AssignmentInConditionCheck",
          true,
          List.of("dfa-style-assigning-instead-comparing", "assignment-in-condition")
      );

  @Override
  public String name() {
    return "assignment-in-condition";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    checkCondition(n.getCondition(), n.getBegin().map(p -> p.line).orElse(-1), ctx);
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    checkCondition(n.getCondition(), n.getBegin().map(p -> p.line).orElse(-1), ctx);
  }

  @Override
  public void visit(DoStmt n, StyleContext ctx) {
    checkCondition(n.getCondition(), n.getBegin().map(p -> p.line).orElse(-1), ctx);
  }

  /**
   * Inspect a boolean-context expression and fire if the top-level operator is {@code =}
   * (assignment) whose LHS is a plain variable. We unwrap any outer {@link EnclosedExpr} since
   * users commonly write {@code if ((x = y))} or {@code if ((isZero = true))}.
   */
  private void checkCondition(Expression cond, int line, StyleContext ctx) {
    if (cond == null) return;
    Expression e = cond;
    // Unwrap outer parentheses / chaining.
    while (e instanceof EnclosedExpr ee) e = ee.getInner();
    if (e instanceof AssignExpr ae && ae.getOperator() == AssignExpr.Operator.ASSIGN) {
      // Skip if LHS is a boolean literal — that's
      // an obvious intentional self-assignment.
      if (ae.getTarget() instanceof BooleanLiteralExpr) return;
      String target = ae.getTarget().toString();
      ctx.report(
          line,
          -1,
          DEFAULT_RULE,
          "Assignment in boolean context: `"
              + cond
              + "` — did you mean `==` instead of `=`? (CWE-481)",
          RuleDef.Severity.LOW,
          target);
    }
  }
}

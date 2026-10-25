/*
 * Assertion boundary check.
 *
 * <p>Off-by-one risk detection in JUnit {@code assert} statements. The
 * classic gotcha is {@code assert i <= max} ? usually the developer
 * meant {@code assert i < max} (or vice-versa for the lower bound).
 *
 * <p>The previous implementation matched the loose shape
 * {@code assert-condition.toString().contains("<=")} which fired on
 * anything nested in a conjunction ({@code assert i <= max && j >= 0})
 * — wildly over-broad. The new implementation inspects only the
 * outermost relational operator of the assert's check expression.
 *
 * <p>Special cases:
 * <ul>
 *   <li>{@code assert i <= max} → fire (auditor verifies strict-less).
 *   <li>{@code assert i >= 0} → fire (auditor verifies strict-greater).
 *   <li>{@code assert i < max && j > 0} → no fire (strict on both).
 *   <li>{@code assert i <= max && i < max} → fire (the
 *       {@code i <= max} conjunct is the suspect one).
 * </ul>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.AssertStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class AssertionBoundaryCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-assertion-boundary", "ASSERTION_BOUNDARY"),
          "Assertion uses <= or >= boundary — verify it should not be strict < / >",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AssertionBoundaryCheck",
          /* enabled */ true,
          List.of("ast-style-assertion-boundary", "assertion-boundary"));

  @Override
  public String name() {
    return "assertion-boundary";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(AssertStmt a, StyleContext ctx) {
    Expression check = unwrap(a.getCheck());
    BoundaryHit hit = scan(check);
    if (hit == null) return;
    int line = a.getBegin().map(p -> p.line).orElse(-1);
    int col = a.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Assertion uses `"
            + hit.operator
            + "` boundary ("
            + hit.snippet
            + ") — review: strict `<`/`>` may be intended",
        RuleDef.Severity.LOW,
        hit.snippet);
  }

  /** Strip parenthesised wrappers so {@code (a <= b)} becomes {@code a <= b}. */
  private static Expression unwrap(Expression e) {
    while (e instanceof EnclosedExpr ee) {
      e = ee.getInner();
    }
    return e;
  }

  /**
   * Recursively scan the outermost relational operators. Returns the first {@code <=} / {@code >=}
   * hit; if the outermost operator is {@code <} / {@code >}, returns null even if a nested
   * conjunction contains {@code <=} (nested hits are acceptable when the top-level guard is
   * strict). For {@code &&} / {@code ||}, both sides are scanned ? the audit fires once per assert
   * (on the first hit).
   */
  private static BoundaryHit scan(Expression e) {
    if (e == null) return null;
    if (e instanceof BinaryExpr be) {
      switch (be.getOperator()) {
        case LESS_EQUALS:
          return new BoundaryHit("<=", be.toString());
        case GREATER_EQUALS:
          return new BoundaryHit(">=", be.toString());
        case AND:
        case OR:
          BoundaryHit left = scan(be.getLeft());
          return left != null ? left : scan(be.getRight());
        default:
          return null;
      }
    }
    return null;
  }

  /** Hit descriptor used to enrich the finding message. */
  private record BoundaryHit(String operator, String snippet) {}
}

package org.plumb.style.performance;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** BigDecimal.equals is value-and-scale, use compareTo. */
public final class BigDecimalEqualsCheck implements AstChecker {

  private StyleContext ctx;
  private RuleDef rule;

  @Override
  public String name() {
    return "big-decimal-equals";
  }

  @Override
  public void init(StyleContext ctx) {
    this.ctx = ctx;
    this.rule = ctx.rules().byId(RuleRegistry.BIG_DECIMAL_EQUALS).orElse(null);
  }

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!n.getNameAsString().equals("equals")) return;
    if (n.getArguments().size() != 1) return;
    // Heuristic: receiver looks BigDecimal-y.
    if (!looksLikeBigDecimal(n.getScope().orElse(null))) return;
    int line = n.getRange().map(r -> r.begin.line).orElse(-1);
    int col = n.getRange().map(r -> r.begin.column).orElse(-1);
    this.ctx.report(
        line,
        col,
        this.rule,
        "BigDecimal.equals is value-and-scale; use " + "compareTo(other) == 0 to compare values",
        RuleDef.Severity.MEDIUM,
        n.toString());
  }

  /**
   * Heuristic: returns true if the expression looks like a BigDecimal. We accept the simple-name
   * form (e.g. declared as and the FQN form (e.g. . The heuristic is intentionally loose; the false
   * positive rate is bounded by the requirement that the variable name includes "decimal" /
   * "amount" / "price" / "total" / "money" / "rate". A future R-round can add proper type
   * resolution.
   */
  private static boolean looksLikeBigDecimal(Expression e) {
    if (e == null) return false;
    String text = e.toString().toLowerCase();
    if (text.contains("bigdecimal")) return true;
    if (text.contains("decimal")) return true;
    if (text.contains("amount")
        || text.contains("price")
        || text.contains("total")
        || text.contains("money")
        || text.contains("rate")
        || text.contains("balance")) {
      return true;
    }
    return false;
  }
}

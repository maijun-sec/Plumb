package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** ambiguous operator precedence, add explicit parentheses. */
public final class AmbiguousOperatorPrecedenceCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-ambiguous-precedence", "AMBIGUOUS_PRECEDENCE"),
          "ambiguous operator precedence, add explicit parentheses",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-783"),
          "org.plumb.style.AmbiguousOperatorPrecedenceCheck",
          true,
          List.of("ast-style-ambiguous-precedence", "ambiguous-precedence")
      );

  private static final Set<Operator> BITWISE_OPS =
      Set.of(Operator.BINARY_AND, Operator.BINARY_OR, Operator.XOR);
  private static final Set<Operator> EQUALITY_OPS = Set.of(Operator.EQUALS, Operator.NOT_EQUALS);

  @Override
  public String name() {
    return "ambiguous-precedence";
  }

  @Override
  public void init(StyleContext ctx) {
    // no per-file state needed
  }

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    // If the parent already wrapped this expression in
    // parens, we don't fire (EnclosedExpr).
    if (n.getParentNode().isPresent() && n.getParentNode().get() instanceof EnclosedExpr) {
      return;
    }
    Operator outer = n.getOperator();
    // Only fire on equality ops wrapping bitwise, or
    // bitwise ops with equality subexpressions.
    Expression lhs = n.getLeft();
    Expression rhs = n.getRight();
    if (EQUALITY_OPS.contains(outer) && (isBitwise(lhs) || isBitwise(rhs))) {
      fire(ctx, n);
      return;
    }
    if (BITWISE_OPS.contains(outer)) {
      Operator lop = operatorOf(lhs);
      Operator rop = operatorOf(rhs);
      if ((lop != null && EQUALITY_OPS.contains(lop))
          || (rop != null && EQUALITY_OPS.contains(rop))) {
        fire(ctx, n);
      }
    }
  }

  private static boolean isBitwise(Expression e) {
    if (!(e instanceof BinaryExpr be)) return false;
    return BITWISE_OPS.contains(be.getOperator());
  }

  private static Operator operatorOf(Expression e) {
    if (e instanceof BinaryExpr be) return be.getOperator();
    return null;
  }

  private void fire(StyleContext ctx, BinaryExpr n) {
    ctx.report(
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Mixed bitwise and equality operators without explicit parentheses: " + n,
        RuleDef.Severity.MEDIUM,
        n.toString());
  }
}

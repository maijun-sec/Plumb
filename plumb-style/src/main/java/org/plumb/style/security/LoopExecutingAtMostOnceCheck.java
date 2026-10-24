package org.plumb.style.security;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** loop executes at most once : refactor to a plain statement. */
public final class LoopExecutingAtMostOnceCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-loop-once", "LOOP_ONCE"),
          "loop executes at most once : refactor to a plain statement",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LoopExecutingAtMostOnceCheck",
          true,
          List.of("ast-security-executing-at-most-once", "loop-once"));

  @Override
  public String name() {
    return "loop-once";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    // Compare condition: `i < N` where N is a literal ⇒?1.
    Expression cond = n.getCompare().orElse(null);
    if (cond instanceof BinaryExpr be) {
      if (isLiteralBound(be.getRight(), 1) || isLiteralBound(be.getLeft(), 1)) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "for-loop bound is a literal ⇒?1 : loop runs at most once",
            RuleDef.Severity.LOW,
            "for");
      }
    }
  }

  private static boolean isLiteralBound(Expression e, int max) {
    if (!(e instanceof IntegerLiteralExpr lit)) return false;
    try {
      return lit.asNumber().intValue() <= max;
    } catch (Exception ex) {
      return false;
    }
  }
}

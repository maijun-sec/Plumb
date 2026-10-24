package org.plumb.style.bug;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** for-loop incrementer is unusual (non-1 step or wrong direction). */
public final class JumbledIncrementerCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-jumbled-incrementer", "JUMBLED_INCREMENTER"),
          "for-loop incrementer is unusual (non-1 step or wrong direction)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.JumbledIncrementerCheck",
          true,
          List.of("ast-bug-jumbled-incrementer", "bug-jumbled-incrementer"));

  @Override
  public String name() {
    return "jumbled-incrementer-step";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    if (n.getUpdate().isEmpty()) return;
    // Inspect each update: flag i++ when the loop
    // condition is i < 0 (going wrong way), and
    // flag i += 2 (skipping).
    for (var update : n.getUpdate()) {
      if (update instanceof UnaryExpr u
          && u.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT) {
        // i-- while i < N : flag it.
        if (n.getCompare().isPresent()
            && n.getCompare().get() instanceof BinaryExpr b
            && b.getOperator() == BinaryExpr.Operator.LESS) {
          int line = n.getBegin().map(p -> p.line).orElse(-1);
          int col = n.getBegin().map(p -> p.column).orElse(-1);
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "i-- inside a for-loop with i < bound : confusing",
              RuleDef.Severity.LOW,
              "--");
        }
      }
      if (update instanceof AssignExpr a
          && a.getOperator() == AssignExpr.Operator.PLUS
          && a.getValue() instanceof IntegerLiteralExpr v
          && !"1".equals(v.getValue())) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "for-loop step is " + v.getValue() + " (not 1)",
            RuleDef.Severity.LOW,
            "+=");
      }
    }
  }
}

package org.plumb.style.error;

import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** instanceof in catch : use multiple catch clauses. */
public final class AvoidInstanceofChecksInCatchClauseCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-instanceof-in-catch", "INSTANCEOF_IN_CATCH"),
          "instanceof in catch : use multiple catch clauses",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.AvoidInstanceofChecksInCatchClauseCheck",
          true,
          List.of("ast-error-instanceof-in-catch", "instanceof-in-catch")
      );

  @Override
  public String name() {
    return "instanceof-in-catch";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(InstanceOfExpr n, StyleContext ctx) {
    if (n.findAncestor(CatchClause.class).isEmpty()) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "instanceof in catch : use multiple catch clauses",
        RuleDef.Severity.MEDIUM,
        "instanceof");
  }
}

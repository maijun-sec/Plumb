package org.plumb.style.error;

import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.TryStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** identical catch branches : merge with multi-catch. */
public final class IdenticalCatchBranchesCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-identical-catch", "IDENTICAL_CATCH"),
          "identical catch branches : merge with multi-catch",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.IdenticalCatchBranchesCheck",
          true,
          List.of("ast-error-identical-catch-branches", "identical-catch"));

  @Override
  public String name() {
    return "identical-catch";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(TryStmt n, StyleContext ctx) {
    if (n.getCatchClauses().size() < 2) return;
    Set<String> bodies = new HashSet<>();
    for (CatchClause c : n.getCatchClauses()) {
      bodies.add(c.getBody().toString());
    }
    if (bodies.size() < n.getCatchClauses().size()) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Multiple catch clauses have identical bodies : merge with multi-catch",
          RuleDef.Severity.MEDIUM,
          "catch");
    }
  }
}

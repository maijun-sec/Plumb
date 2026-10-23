package org.plumb.style.style;

import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** empty statement (;) : remove it. */
public final class EmptyStatementUsageCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-empty-statement-2", "EMPTY_STATEMENT_2"),
          "empty statement (;) : remove it",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyStatementUsageCheck",
          true,
          List.of("ast-style-statement-empty-usage", "empty-statement")
      );

  @Override
  public String name() {
    return "empty-statement";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(EmptyStmt n, StyleContext ctx) {
    // Skip empty statements used as the body of a
    // loop : `while (cond);` and `for (...);` are
    // intentional. contract.
    if (n.getParentNode().isPresent()) {
      var parent = n.getParentNode().get();
      if (parent instanceof WhileStmt
          || parent instanceof ForStmt
          || parent instanceof ForEachStmt) {
        return;
      }
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line, col, DEFAULT_RULE, "Empty statement (;) : remove it", RuleDef.Severity.LOW, ";");
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** nested if statements can be collapsed into one with &&. */
public final class CollapsibleIfCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-collapsible-if", "COLLAPSIBLE_IF"),
          "nested if statements can be collapsed into one with &&",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CollapsibleIfCheck",
          true,
          List.of("ast-style-collapsible-if", "collapsible-if")
      );

  @Override
  public String name() {
    return "collapsible-if";
  }

  @Override
  public void init(StyleContext ctx) {
    // no per-file state needed
  }

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    // The "then" branch must be a single if-statement
    // (not an `else` chain, not a block of multiple
    // statements). If it is, we can collapse.
    final Statement thenStmt = n.getThenStmt();
    if (thenStmt == null) return;
    Statement inner = thenStmt;
    // If the then-branch is wrapped in a block
    // { if (b) {... } }, we look inside the block.
    if (inner instanceof BlockStmt block) {
      if (block.getStatements().size() != 1) return;
      inner = block.getStatements().get(0);
    }
    // The inner statement must be an IfStmt with NO
    // else clause (else chains can't be naively
    // collapsed : they have different semantics).
    if (!(inner instanceof IfStmt innerIf)) return;
    if (innerIf.getElseStmt().isPresent()) return;
    // Fire on the inner if-statement (the one to
    // collapse with the outer). Reporting on the
    // inner's location is more useful for fixing.
    ctx.report(
        innerIf.getBegin().map(p -> p.line).orElse(-1),
        innerIf.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Nested if-statement can be collapsed with outer if using &&",
        RuleDef.Severity.LOW,
        innerIf.toString());
  }
}

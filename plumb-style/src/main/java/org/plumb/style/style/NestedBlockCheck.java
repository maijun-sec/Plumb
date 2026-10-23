package org.plumb.style.style;

import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** nested block : extract to a method. */
public final class NestedBlockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-nested-block", "NESTED_BLOCK"),
          "nested block : extract to a method",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NestedBlockCheck",
          true,
          List.of("ast-style-nested-block", "nested-block")
      );

  @Override
  public String name() {
    return "nested-block";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BlockStmt n, StyleContext ctx) {
    // Only fire when the parent is a BlockStmt.
    if (!(n.getParentNode().orElse(null) instanceof BlockStmt)) return;
    // Don't fire on switch block or try/catch.
    if (n.getParentNode().orElse(null) instanceof com.github.javaparser.ast.stmt.SwitchEntry)
      return;
    if (n.getParentNode().orElse(null) instanceof com.github.javaparser.ast.stmt.CatchClause)
      return;
    if (n.getParentNode().orElse(null) instanceof com.github.javaparser.ast.stmt.TryStmt) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line, col, DEFAULT_RULE, "Nested block : extract to a method", RuleDef.Severity.LOW, "{");
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.stmt.SwitchStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** switch statement has no cases. */
public final class EmptySwitchStatementsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-empty-switch", "EMPTY_SWITCH"),
          "switch statement has no cases",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.EmptySwitchStatementsCheck",
          true,
          List.of("ast-style-switch-statements", "empty-switch"));

  @Override
  public String name() {
    return "empty-switch";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SwitchStmt n, StyleContext ctx) {
    if (n.getEntries().isEmpty()) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "switch has no cases : remove it",
          RuleDef.Severity.MEDIUM,
          "switch");
    }
  }
}

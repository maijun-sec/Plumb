package org.plumb.style.style;

import com.github.javaparser.ast.stmt.SwitchStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** switch should have a default branch. */
public final class SwitchWithoutDefaultCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-switch-no-default", "SWITCH_NO_DEFAULT"),
          "switch should have a default branch",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.SwitchWithoutDefaultCheck",
          true,
          List.of("ast-style-switch-without-default", "switch-no-default"));

  @Override
  public String name() {
    return "switch-no-default";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SwitchStmt n, StyleContext ctx) {
    boolean hasDefault = n.getEntries().stream().anyMatch(e -> e.getLabels().isEmpty());
    if (hasDefault) return;
    // Skip if the switch is over an enum (compiler
    // checks exhaustiveness).
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line, col, DEFAULT_RULE, "switch has no default branch", RuleDef.Severity.MEDIUM, "switch");
  }
}

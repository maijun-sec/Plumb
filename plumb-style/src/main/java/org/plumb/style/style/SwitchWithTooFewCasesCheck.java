package org.plumb.style.style;

import com.github.javaparser.ast.stmt.SwitchStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** switch has fewer than 3 cases : use if-else. */
public final class SwitchWithTooFewCasesCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-switch-few-cases", "SWITCH_FEW_CASES"),
          "switch has fewer than 3 cases : use if-else",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SwitchWithTooFewCasesCheck",
          true,
          List.of("ast-style-switch-few-cases", "switch-few-cases"));

  @Override
  public String name() {
    return "switch-few-cases";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SwitchStmt n, StyleContext ctx) {
    int caseCount = (int) n.getEntries().stream().filter(e -> !e.getLabels().isEmpty()).count();
    if (caseCount >= 3) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "switch has " + caseCount + " cases (< 3) : use if-else",
        RuleDef.Severity.LOW,
        "switch");
  }
}

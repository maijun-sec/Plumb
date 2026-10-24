package org.plumb.style.style;

import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** switch default should be the last case. */
public final class SwitchDefaultLastCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-switch-default-last", "SWITCH_DEFAULT_LAST"),
          "switch default should be the last case",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SwitchDefaultLastCheck",
          true,
          List.of("ast-style-switch-default-last", "switch-default-last"));

  @Override
  public String name() {
    return "switch-default-last";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SwitchStmt n, StyleContext ctx) {
    java.util.List<SwitchEntry> entries = n.getEntries();
    for (int i = 0; i < entries.size() - 1; i++) {
      if (entries.get(i).getLabels().isEmpty()) {
        int line = entries.get(i).getBegin().map(p -> p.line).orElse(-1);
        int col = entries.get(i).getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "default case is not the last entry of the switch",
            RuleDef.Severity.LOW,
            "default");
        return;
      }
    }
  }
}

package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** switch should have a default case. */
public final class MissingSwitchDefaultCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-missing-switch-default", "MISSING_SWITCH_DEFAULT"),
          "switch should have a default case",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MissingSwitchDefaultCheck",
          true,
          List.of("ast-style-missing-switch-default", "missing-switch-default"));

  @Override
  public String name() {
    return "missing-switch-default";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SwitchStmt s, StyleContext ctx) {
    boolean hasDefault = false;
    for (SwitchEntry e : s.getEntries()) {
      if (e.getLabels().isEmpty()) {
        hasDefault = true;
        break;
      }
    }
    if (!hasDefault) {
      int line = s.getBegin().map(p -> p.line).orElse(-1);
      int col = s.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Switch should have a default case",
          RuleDef.Severity.LOW,
          "switch");
    }
  }
}

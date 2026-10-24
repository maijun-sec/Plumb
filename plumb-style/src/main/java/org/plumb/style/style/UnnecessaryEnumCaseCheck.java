package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.stmt.SwitchStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Unnecessary case in enum switch. */
public final class UnnecessaryEnumCaseCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unnecessaryenumcase-sb", "UNNECESSARYENUMCASE_SB"),
          "Unnecessary case in enum switch",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnnecessaryEnumCaseCheck",
          true,
          List.of("ast-style-unnecessary-enum-case", "unnecessaryenumcase_sb"));

  @Override
  public String name() {
    return "unnecessaryenumcase-sb";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SwitchStmt sw, StyleContext ctx) {
    String s = sw.toString();
    if (s.contains("default:")) {
      int line = sw.getBegin().map(p -> p.line).orElse(-1);
      int col = sw.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Unnecessary case in enum switch",
          RuleDef.Severity.LOW,
          "switch");
    }
  }
}

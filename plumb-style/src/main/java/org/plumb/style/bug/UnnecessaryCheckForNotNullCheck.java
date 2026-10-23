package org.plumb.style.bug;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Unnecessary check for not null. */
public final class UnnecessaryCheckForNotNullCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-unnecessaryfornotnull-sb", "UNNECESSARYFORNOTNULL_SB"),
          "Unnecessary check for not null",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnnecessaryCheckForNotNullCheck",
          true,
          List.of("ast-bug-unnecessary-check-not-null", "unnecessaryfornotnull_sb")
      );

  @Override
  public String name() {
    return "unnecessaryfornotnull-sb";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr b, StyleContext ctx) {
    String s = b.toString();
    if (s.contains(".isEmpty()") && s.contains("null")) {
      int line = b.getBegin().map(p -> p.line).orElse(-1);
      int col = b.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "Unnecessary check for not null", RuleDef.Severity.LOW, s);
    }
  }
}

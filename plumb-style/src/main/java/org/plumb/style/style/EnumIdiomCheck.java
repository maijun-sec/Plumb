package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.body.EnumDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Enum idiom check. */
public final class EnumIdiomCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-enumidiom-sb", "ENUMIDIOM_SB"),
          "Enum idiom check",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EnumIdiomCheck",
          true,
          List.of("ast-style-idiom-enum", "enumidiom_sb")
      );

  @Override
  public String name() {
    return "enumidiom-sb";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(EnumDeclaration e, StyleContext ctx) {
    if (!e.getEntries().isEmpty() && e.getEntries().size() > 1) {
      int line = e.getBegin().map(p -> p.line).orElse(-1);
      int col = e.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "Enum idiom check", RuleDef.Severity.LOW, e.getNameAsString());
    }
  }
}

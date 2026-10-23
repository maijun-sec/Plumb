package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.stmt.SwitchStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** switch case fallthrough without // fallthrough. */
public final class FallThroughCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-fallthrough", "FALLTHROUGH"),
          "switch case fallthrough without // fallthrough",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.FallThroughCheck",
          true,
          List.of("ast-style-fallthrough", "fallthroughcheck")
      );

  @Override
  public String name() {
    return "fallthrough";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SwitchStmt s, StyleContext ctx) {
java.util.List<com.github.javaparser.ast.stmt.SwitchEntry> entries = s.getEntries();
for (int i = 0; i + 1 < entries.size(); i++) {
  com.github.javaparser.ast.stmt.SwitchEntry e = entries.get(i);
  com.github.javaparser.ast.stmt.SwitchEntry next = entries.get(i + 1);
  if (e.getLabels().isEmpty() || e.getStatements().isEmpty()) return;
  com.github.javaparser.ast.stmt.Statement last =
      e.getStatements().get(e.getStatements().size() - 1);
  boolean hasFallthrough = false;
  if (last instanceof com.github.javaparser.ast.stmt.ReturnStmt) hasFallthrough = true;
  else if (last instanceof com.github.javaparser.ast.stmt.BreakStmt) hasFallthrough = true;
  else if (last instanceof com.github.javaparser.ast.stmt.ContinueStmt) hasFallthrough = true;
  else if (last instanceof com.github.javaparser.ast.stmt.ThrowStmt) hasFallthrough = true;
  if (!hasFallthrough) {
    int line = e.getBegin().map(p -> p.line).orElse(-1);
    int col = e.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Switch case fallthrough without break/return/throw",
        RuleDef.Severity.LOW,
        "case");
  }
}
  }
}

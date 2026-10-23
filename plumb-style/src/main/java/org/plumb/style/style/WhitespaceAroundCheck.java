package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Whitespace around token. */
public final class WhitespaceAroundCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-whitespacearound", "WHITESPACEAROUND"),
          "Whitespace around token",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.WhitespaceAroundCheck",
          true,
          List.of("ast-style-whitespace-around", "whitespacearound")
      );

  @Override
  public String name() {
    return "whitespacearound";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr b, StyleContext ctx) {
    String s = b.toString();
    if (s.matches(".*[a-zA-Z0-9][+][a-zA-Z0-9].*")
        || s.matches(".*[a-zA-Z0-9][-][a-zA-Z0-9].*")) {
      int line = b.getBegin().map(p -> p.line).orElse(-1);
      int col = b.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Whitespace around operator",
          RuleDef.Severity.LOW,
          b.getOperator().toString());
    }
  }
}

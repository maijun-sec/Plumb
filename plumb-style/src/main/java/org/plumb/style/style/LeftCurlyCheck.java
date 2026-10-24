package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** left curly brace should be on the same line as declaration. */
public final class LeftCurlyCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-left-curly", "LEFT_CURLY"),
          "left curly brace should be on the same line as declaration",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LeftCurlyCheck",
          true,
          List.of("ast-style-curly", "left-curly"));

  @Override
  public String name() {
    return "left-curly";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (m.getBody().isEmpty()) return;
    int declLine = m.getBegin().map(p -> p.line).orElse(-1);
    int braceLine = m.getBody().get().getBegin().map(p -> p.line).orElse(-1);
    if (braceLine > declLine) {
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Opening brace should be on the same line as the method declaration",
          RuleDef.Severity.LOW,
          m.getNameAsString());
    }
  }
}

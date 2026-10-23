package org.plumb.style.bug;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Unused null check in equals. */
public final class UnusedNullCheckInEqualsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-unusednullinequals", "UNUSEDNULLINEQUALS"),
          "Unused null check in equals",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnusedNullCheckInEqualsCheck",
          true,
          List.of("ast-bug-unused-null-check-equals", "unusednullinequals")
      );

  @Override
  public String name() {
    return "unusednullinequals";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (m.getNameAsString().equals("equals") && m.getBody().isPresent()) {
      String body = m.getBody().get().toString();
      if (body.contains("== null") || body.contains("!= null")) {
        int line = m.getBegin().map(p -> p.line).orElse(-1);
        int col = m.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Unused null check in equals",
            RuleDef.Severity.LOW,
            m.getNameAsString());
      }
    }
  }
}

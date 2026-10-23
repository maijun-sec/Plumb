package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.stmt.SynchronizedStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Public field accessed in synchronized context. */
public final class PublicFieldAccessedInSynchronizedContextCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-publicfieldaccessedinsynchronizedcontext-sb", "PUBLICFIELDACCESSEDINSYNCHRONIZEDCONTEXT_SB"),
          "Public field accessed in synchronized context",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.PublicFieldAccessedInSynchronizedContextCheck",
          true,
          List.of("ast-style-public-field-sync-context", "publicfieldaccessedinsynchronizedcontext_sb")
      );

  @Override
  public String name() {
    return "publicfieldaccessedinsynchronizedcontext-sb";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SynchronizedStmt s, StyleContext ctx) {
    String body = s.getBody().toString();
    if (body.contains("public ")) {
      int line = s.getBegin().map(p -> p.line).orElse(-1);
      int col = s.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Public field accessed in synchronized context",
          RuleDef.Severity.MEDIUM,
          "sync");
    }
  }
}

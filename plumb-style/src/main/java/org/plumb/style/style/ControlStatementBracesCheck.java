package org.plumb.style.style;

import com.github.javaparser.ast.stmt.*;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** control statement should always use braces. */
public final class ControlStatementBracesCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-control-statement-braces", "CONTROL_STATEMENT_BRACES"),
          "control statement should always use braces",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ControlStatementBracesCheck",
          true,
          List.of("ast-style-braces", "control-statement-braces")
      );

  @Override
  public String name() {
    return "control-statement-braces";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    check(n.getThenStmt(), "if", ctx);
    check(n.getElseStmt().orElse(null), "else", ctx);
  }

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    check(n.getBody(), "for", ctx);
  }

  @Override
  public void visit(ForEachStmt n, StyleContext ctx) {
    check(n.getBody(), "for-each", ctx);
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    check(n.getBody(), "while", ctx);
  }

  private void check(Statement body, String kind, StyleContext ctx) {
    if (body == null) return;
    if (body instanceof com.github.javaparser.ast.stmt.BlockStmt) return;
    int line = body.getBegin().map(p -> p.line).orElse(-1);
    int col = body.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        kind + " statement should use braces { }",
        RuleDef.Severity.LOW,
        kind);
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.stmt.*;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** control statement with empty body. */
public final class EmptyControlStatementCheck implements AstChecker {

  private StyleContext ctx;
  private RuleDef rule;

  @Override
  public String name() {
    return "empty-control-statement";
  }

  @Override
  public void init(StyleContext ctx) {
    this.ctx = ctx;
    this.rule = ctx.rules().byId(RuleRegistry.STYLE_EMPTY_CONTROL_STATEMENT).orElse(null);
  }

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    if (isEmpty(n.getThenStmt())) {
      report(n, "if");
    }
    n.getElseStmt()
        .ifPresent(
            es -> {
              if (isEmpty(es)) {
                report(n, "if-else");
              }
            });
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    if (isEmpty(n.getBody())) report(n, "while");
  }

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    if (isEmpty(n.getBody())) report(n, "for");
  }

  @Override
  public void visit(ForEachStmt n, StyleContext ctx) {
    if (isEmpty(n.getBody())) report(n, "for-each");
  }

  @Override
  public void visit(DoStmt n, StyleContext ctx) {
    if (isEmpty(n.getBody())) report(n, "do-while");
  }

  /**
   * an empty control-statement body is either a bare an {@link
   * com.github.javaparser.ast.stmt.EmptyStmt}) or an empty block. The previous version only matched
   * {@code.isEmptyStmt()}, which misses the form (both). if/while/for/foreach/do-while with either
   * empty statement or empty block.
   */
  private static boolean isEmpty(com.github.javaparser.ast.stmt.Statement s) {
    if (s == null) return true;
    if (s.isEmptyStmt()) return true;
    if (s instanceof com.github.javaparser.ast.stmt.BlockStmt b) {
      return b.getStatements().isEmpty();
    }
    return false;
  }

  private void report(Statement s, String kind) {
    int line = s.getRange().map(r -> r.begin.line).orElse(-1);
    int col = s.getRange().map(r -> r.begin.column).orElse(-1);
    this.ctx.report(
        line,
        col,
        this.rule,
        "EmptyControlStatement: " + kind + " statement has empty body",
        RuleDef.Severity.LOW,
        kind);
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.stmt.*;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** loop has multiple break/continue : refactor. */
public final class LoopsWithMultipleBreaksCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-loop-multi-breaks", "LOOP_MULTI_BREAKS"),
          "loop has multiple break/continue : refactor",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LoopsWithMultipleBreaksCheck",
          true,
          List.of("ast-style-multiple-breaks", "loop-multi-breaks"));

  @Override
  public String name() {
    return "loop-multi-breaks";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    check(n, "for", ctx);
  }

  @Override
  public void visit(ForEachStmt n, StyleContext ctx) {
    check(n, "for-each", ctx);
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    check(n, "while", ctx);
  }

  @Override
  public void visit(DoStmt n, StyleContext ctx) {
    check(n, "do", ctx);
  }

  private void check(com.github.javaparser.ast.Node loop, String kind, StyleContext ctx) {
    long breaks = loop.findAll(BreakStmt.class).size() + loop.findAll(ContinueStmt.class).size();
    if (breaks <= 1) return;
    int line = loop.getBegin().map(p -> p.line).orElse(-1);
    int col = loop.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        kind + " loop has " + breaks + " break/continue : refactor",
        RuleDef.Severity.LOW,
        kind);
  }
}

package org.plumb.style.error;

import com.github.javaparser.ast.stmt.CatchClause;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Exception is dropped ? empty {@code catch} block with no logging or rethrow.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code visit(CompilationUnit) +
 * findAll(CatchClause.class)} before.
 */
public final class DroppedExceptionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-dropped-exception", "DROPPED_EXCEPTION"),
          "Exception is dropped",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DroppedExceptionCheck",
          true,
          List.of("ast-error-dropped-exception", "dropped-exception"));

  @Override
  public String name() {
    return "dropped-exception";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CatchClause cc, StyleContext ctx) {
    if (!cc.getBody().getStatements().isEmpty()) return;
    int line = cc.getBegin().map(p -> p.line).orElse(-1);
    int col = cc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Exception is dropped",
        RuleDef.Severity.MEDIUM,
        cc.getParameter().getNameAsString());
  }
}

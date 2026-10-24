package org.plumb.style.style;

import com.github.javaparser.ast.stmt.TryStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** empty finally block : remove it. */
public final class EmptyFinallyBlockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-empty-finally-block", "EMPTY_FINALLY_BLOCK"),
          "empty finally block : remove it",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyFinallyBlockCheck",
          true,
          List.of("ast-style-finally-block", "empty-finally-block"));

  @Override
  public String name() {
    return "empty-finally-block";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(TryStmt n, StyleContext ctx) {
    if (n.getFinallyBlock().isEmpty()) return;
    if (!n.getFinallyBlock().get().getStatements().isEmpty()) return;
    int line = n.getFinallyBlock().get().getBegin().map(p -> p.line).orElse(-1);
    int col = n.getFinallyBlock().get().getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Empty finally block : remove it",
        RuleDef.Severity.LOW,
        "finally");
  }
}

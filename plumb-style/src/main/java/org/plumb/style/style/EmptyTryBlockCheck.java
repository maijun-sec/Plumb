package org.plumb.style.style;

import com.github.javaparser.ast.stmt.TryStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** empty try block : remove it. */
public final class EmptyTryBlockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-empty-try-block", "EMPTY_TRY_BLOCK"),
          "empty try block : remove it",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyTryBlockCheck",
          false,
          List.of("ast-style-try-block", "empty-try-block")
      ); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832 baseline)

  @Override
  public String name() {
    return "empty-try-block";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(TryStmt n, StyleContext ctx) {
    if (n.getTryBlock() == null) return;
    if (!n.getTryBlock().getStatements().isEmpty()) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(line, col, DEFAULT_RULE, "Empty try block : remove it", RuleDef.Severity.LOW, "try");
  }
}

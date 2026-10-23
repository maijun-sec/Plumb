package org.plumb.style.style;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** empty synchronized block (ESYNC_EMPTY_SYNC). */
public final class EsyncEmptySyncCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-esync-empty", "ESYNC_EMPTY"),
          "empty synchronized block (ESYNC_EMPTY_SYNC)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EsyncEmptySyncCheck",
          true,
          List.of("ast-style-empty-sync", "esync-empty")
      );

  @Override
  public String name() {
    return "esync-empty";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SynchronizedStmt n, StyleContext ctx) {
    BlockStmt body = n.getBody();
    if (body.getStatements().isEmpty()) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Empty synchronized block : no statements inside",
          RuleDef.Severity.LOW,
          "synchronized");
    }
  }
}

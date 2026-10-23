package org.plumb.style.style;

import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** empty while-statement (body is a bare ';'). */
public final class EmptyWhileStmtCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-empty-while", "EMPTY_WHILE"),
          "empty while-statement (body is a bare ';')",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.EmptyWhileStmtCheck",
          true,
          List.of("ast-style-while-stmt", "empty-while")
      );

  @Override
  public String name() {
    return "empty-while";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    if (!(n.getBody() instanceof EmptyStmt)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Empty while-statement : remove it",
        RuleDef.Severity.MEDIUM,
        "while");
  }
}

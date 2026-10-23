package org.plumb.style.style;

import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** if/else if chain : consider switch. */
public final class IfElseStmtCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-if-else-stmt", "IF_ELSE_STMT"),
          "if/else if chain : consider switch",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.IfElseStmtCheck",
          true,
          List.of("ast-style-else-stmt", "if-else-stmt")
      );

  @Override
  public String name() {
    return "if-else-stmt";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    Statement elseBranch = n.getElseStmt().orElse(null);
    int chainLength = 0;
    Statement cur = elseBranch;
    while (cur instanceof IfStmt) {
      chainLength++;
      cur = ((IfStmt) cur).getElseStmt().orElse(null);
    }
    if (chainLength < 2) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "if/else if chain of " + (chainLength + 1) + " branches : consider switch",
        RuleDef.Severity.LOW,
        "if");
  }
}

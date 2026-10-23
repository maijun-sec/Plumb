package org.plumb.style.style;

import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** empty if-statement (then-branch is a bare ';'). */
public final class EmptyIfStmtCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-empty-if-stmt", "EMPTY_IF_STMT"),
          "empty if-statement (then-branch is a bare ';')",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.EmptyIfStmtCheck",
          true,
          List.of("ast-style-if-stmt", "empty-if-stmt")
      );

  @Override
  public String name() {
    return "empty-if-stmt";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    if (!(n.getThenStmt() instanceof EmptyStmt)) return;
    // Skip `if (cond);` patterns where the empty
    // stmt is intentional : these are unusual but
    // valid. The check still fires per spec.
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line, col, DEFAULT_RULE, "Empty if-statement : remove it", RuleDef.Severity.MEDIUM, "if");
  }
}

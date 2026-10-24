package org.plumb.style.style;

import com.github.javaparser.ast.stmt.IfStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** if-statement is nested more than 4 levels deep. */
public final class AvoidDeeplyNestedIfStmtsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-deeply-nested-if", "DEEPLY_NESTED_IF"),
          "if-statement is nested more than 4 levels deep",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.AvoidDeeplyNestedIfStmtsCheck",
          true,
          List.of("ast-style-deeply-nested-if-stmts", "deeply-nested-if"));

  private static final int MAX_DEPTH = 4;

  @Override
  public String name() {
    return "deeply-nested-if";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    int depth = 0;
    IfStmt cur = n;
    while (cur != null) {
      depth++;
      cur = cur.findAncestor(IfStmt.class).orElse(null);
    }
    if (depth <= MAX_DEPTH) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "if-statement nested " + depth + " levels deep (limit " + MAX_DEPTH + ")",
        RuleDef.Severity.MEDIUM,
        "if");
  }
}

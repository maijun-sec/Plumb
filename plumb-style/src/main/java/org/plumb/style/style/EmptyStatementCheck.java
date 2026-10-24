package org.plumb.style.style;

import com.github.javaparser.ast.stmt.EmptyStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Empty statement (just a semicolon) is useless. */
public final class EmptyStatementCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-empty-statement", "EMPTY_STATEMENT"),
          "empty statement (just a semicolon) is useless",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyStatementCheck",
          true,
          List.of("ast-style-statement", "style-statement"));

  @Override
  public String name() {
    return "empty-statement";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(EmptyStmt es, StyleContext ctx) {
    int line = es.getBegin().map(p -> p.line).orElse(-1);
    int col = es.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line, col, DEFAULT_RULE, "Empty statement (useless semicolon)", RuleDef.Severity.LOW, ";");
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * multiple statements on the same line.
 *
 * <p>Pattern D: per-method / per-constructor visit. The body is small (a single block), so we count
 * statements by line in one local pass. Replaces visit(CU) + 2 findAll.
 */
public final class OneStatementPerLineCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-one-statement-per-line", "ONE_STATEMENT_PER_LINE"),
          "multiple statements on the same line",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.OneStatementPerLineCheck",
          true,
          List.of("ast-style-statement-per-line", "one-statement-per-line")
      );

  @Override
  public String name() {
    return "one-statement-per-line";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (m.getBody().isPresent()) checkBlock(m.getBody().get(), ctx);
  }

  @Override
  public void visit(ConstructorDeclaration c, StyleContext ctx) {
    checkBlock(c.getBody(), ctx);
  }

  private void checkBlock(BlockStmt block, StyleContext ctx) {
    Map<Integer, Integer> stmtCountByLine = new HashMap<>();
    for (Statement s : block.getStatements()) {
      s.getBegin()
          .ifPresent(
              p -> {
                Integer cur = stmtCountByLine.get(p.line);
                stmtCountByLine.put(p.line, cur == null ? 1 : cur + 1);
              });
    }
    for (Map.Entry<Integer, Integer> e : stmtCountByLine.entrySet()) {
      if (e.getValue() > 1) {
        ctx.report(
            e.getKey(),
            0,
            DEFAULT_RULE,
            e.getValue() + " statements on line " + e.getKey(),
            RuleDef.Severity.LOW,
            "line " + e.getKey());
      }
    }
  }
}

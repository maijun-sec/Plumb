package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** method is too long (limit 50 statements). */
public final class MethodTooLongCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-method-too-long", "METHOD_TOO_LONG"),
          "method is too long (limit 50 statements)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MethodTooLongCheck",
          true);

  private static final int MAX_STATEMENTS = 50;

  @Override
  public String name() {
    return "method-too-long-";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    BlockStmt body = n.getBody().get();
    int count = countStatements(body);
    if (count <= MAX_STATEMENTS) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Method '"
            + n.getNameAsString()
            + "' has "
            + count
            + " statements (limit "
            + MAX_STATEMENTS
            + ")",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }

  private static int countStatements(BlockStmt body) {
    int count = 0;
    for (Statement s : body.getStatements()) {
      count++;
      if (s instanceof BlockStmt nested) {
        count += countStatements(nested);
      }
    }
    return count;
  }
}

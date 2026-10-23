package org.plumb.style.error;

import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.TryStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** nested try-catch : extract the inner try to a method. */
public final class NestedTryCatchCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-nested-try", "NESTED_TRY"),
          "nested try-catch : extract the inner try to a method",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.NestedTryCatchCheck",
          true,
          List.of("ast-error-nested-try-catch", "nested-try")
      );

  @Override
  public String name() {
    return "nested-try";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(TryStmt n, StyleContext ctx) {
    if (n.findAncestor(CatchClause.class).isEmpty()) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Nested try inside catch : extract to a method",
        RuleDef.Severity.MEDIUM,
        "try");
  }
}

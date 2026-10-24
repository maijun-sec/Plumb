package org.plumb.style.error;

import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** catch block does not use the exception. */
public final class CatchExceptionForLoggingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-catch-log", "CATCH_LOG"),
          "catch block does not use the exception",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-390"),
          "org.plumb.style.CatchExceptionForLoggingCheck",
          true,
          List.of("ast-error-exception-log", "catch-log"));

  @Override
  public String name() {
    return "catch-log";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CatchClause n, StyleContext ctx) {
    String paramName = n.getParameter().getNameAsString();
    if (n.getBody() == null) return;
    boolean used = false;
    // Walk the catch body and look for any
    // reference to the exception parameter.
    for (var stmt : n.getBody().getStatements()) {
      for (var ne : stmt.findAll(NameExpr.class)) {
        if (ne.getNameAsString().equals(paramName)) {
          used = true;
          break;
        }
      }
      if (used) break;
    }
    if (used) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "catch block does not use the exception : log it",
        RuleDef.Severity.MEDIUM,
        paramName);
  }
}

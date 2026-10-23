package org.plumb.style.error;

import com.github.javaparser.ast.stmt.CatchClause;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid catching Throwable / Error. */
public final class CatchThrowableCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-catch-throwable", "CATCH_THROWABLE"),
          "avoid catching Throwable / Error",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-396"),
          "org.plumb.style.CatchThrowableCheck",
          true,
          List.of("ast-error-throwable", "catch-throwable")
      );

  @Override
  public String name() {
    return "catch-throwable";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CatchClause n, StyleContext ctx) {
    String typeName = n.getParameter().getType().toString();
    if ("Throwable".equals(typeName)
        || "Error".equals(typeName)
        || typeName.endsWith(".Throwable")
        || typeName.endsWith(".Error")) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "Avoid catching " + typeName, RuleDef.Severity.MEDIUM, typeName);
    }
  }
}

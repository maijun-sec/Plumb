package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Use a logger instead of Throwable.printStackTrace(). */
public final class PrintStackTraceCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-print-stack-trace", "PRINT_STACK_TRACE"),
          "Use a logger instead of Throwable.printStackTrace()",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-209"),
          "org.plumb.style.PrintStackTraceCheck",
          true,
          List.of("ast-style-print-stack-trace-alpha", "print-stack-trace")
      );

  @Override
  public String name() {
    return "print-stack-trace";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!n.getNameAsString().equals("printStackTrace")) return;
    if (!n.getArguments().isEmpty()) return;
    // Scope is a NameExpr : that's the typical `e.printStackTrace()`.
    if (!n.getScope().isPresent()) return;
    if (!(n.getScope().get() instanceof com.github.javaparser.ast.expr.NameExpr)) {
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use a logger instead of " + n.getScope().get() + ".printStackTrace()",
        RuleDef.Severity.MEDIUM,
        "printStackTrace");
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid System.exit() / Runtime.exit() in library code. */
public final class SystemExitUsageCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-system-exit-usage", "SYSTEM_EXIT_USAGE"),
          "avoid System.exit() / Runtime.exit() in library code",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-382"),
          "org.plumb.style.SystemExitUsageCheck",
          true,
          List.of("ast-style-system-exit", "system-exit-usage")
      );

  @Override
  public String name() {
    return "system-exit-usage";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"exit".equals(n.getNameAsString())) return;
    if (!isSystemOrRuntimeScope(n.getScope().orElse(null))) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Avoid System.exit() / Runtime.exit()",
        RuleDef.Severity.MEDIUM,
        "exit");
  }

  private boolean isSystemOrRuntimeScope(com.github.javaparser.ast.expr.Expression scope) {
    if (scope instanceof FieldAccessExpr fa) {
      String owner = fa.getScope().toString();
      String field = fa.getNameAsString();
      // Direct System.exit / Runtime.exit
      // (legacy pre-Java-1.5 access pattern).
      return ("System".equals(owner) && "exit".equals(field))
          || ("Runtime".equals(owner) && "exit".equals(field));
    }
    if (scope instanceof NameExpr ne) {
      return "System".equals(ne.getNameAsString()) || "Runtime".equals(ne.getNameAsString());
    }
    // R721-C3: handle the chained-call form
    //   Runtime.getRuntime().exit(1)
    // The {@code exit(1)} MethodCallExpr has a
    // scope that is itself a MethodCallExpr
    // {@code getRuntime()} whose scope is the
    // {@code Runtime} class / a variable. We
    // recurse one level to detect the
    // {@code getRuntime} pattern AND to detect
    // the {@code System.exit} alias
    // {@code System.getRuntime().exit(...)}
    // (rare but legal).
    if (scope instanceof MethodCallExpr mc) {
      String mname = mc.getNameAsString();
      com.github.javaparser.ast.expr.Expression inner = mc.getScope().orElse(null);
      if (inner instanceof NameExpr ne
          && ("Runtime".equals(ne.getNameAsString()) || "System".equals(ne.getNameAsString()))
          && "getRuntime".equals(mname)) {
        return true;
      }
      // Also match {@code (static Runtime)} accessor:
      // the scope is a bare NameExpr matching
      // "System" and the call name is "getRuntime".
      if (inner instanceof NameExpr ne2
          && "getRuntime".equals(mname)
          && ("System".equals(ne2.getNameAsString()) || "Runtime".equals(ne2.getNameAsString()))) {
        return true;
      }
    }
    return false;
  }
}

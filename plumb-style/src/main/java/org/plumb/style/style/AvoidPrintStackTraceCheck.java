package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid printStackTrace() : use a logger. */
public final class AvoidPrintStackTraceCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-avoid-print-stack-trace", "AVOID_PRINT_STACK_TRACE"),
          "avoid printStackTrace() : use a logger",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-209"),
          "org.plumb.style.AvoidPrintStackTraceCheck",
          true,
          List.of("legacy-print-stack-trace", "avoid-print-stack-trace"));

  /** Catch-parameter names in the current method. */
  private Set<String> catchParamNames = new HashSet<>();

  @Override
  public String name() {
    return "avoid-print-stack-trace";
  }

  @Override
  public void init(StyleContext ctx) {
    catchParamNames.clear();
  }

  @Override
  public void finish(StyleContext ctx) {
    catchParamNames.clear();
  }

  // P1-1-2026-10-18: reset catchParamNames at the start of each method to
  // prevent cross-method leak (a previous method's catch param would otherwise be
  // matched against a local variable / parameter of the same name in the next method).
  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    catchParamNames.clear();
  }

  @Override
  public void visit(CatchClause n, StyleContext ctx) {
    catchParamNames.add(n.getParameter().getNameAsString());
  }

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"printStackTrace".equals(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    if (!(n.getScope().get() instanceof NameExpr ne)) return;
    if (!catchParamNames.contains(ne.getNameAsString())) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Avoid printStackTrace() : use a logger",
        RuleDef.Severity.MEDIUM,
        "printStackTrace");
  }
}

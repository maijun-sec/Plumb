package org.plumb.style.error;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
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

/** ParseException caught and silently swallowed. */
public final class ParseErrorLoggingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-parse-error-logging", "PARSE_ERROR_LOGGING"),
          "ParseException caught and silently swallowed",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-390"),
          "org.plumb.style.ParseErrorLoggingCheck",
          true,
          List.of("ast-error-error-logging", "parse-error-logging"));

  /** Catch-parameter names in the current method. */
  private Set<String> catchParamNames = new HashSet<>();

  @Override
  public String name() {
    return "parse-error-logging";
  }

  @Override
  public void init(StyleContext ctx) {
    catchParamNames.clear();
  }

  @Override
  public void finish(StyleContext ctx) {
    catchParamNames.clear();
  }

  // R786-P1-1-2026-10-18: reset catchParamNames at the start of each method to
  // prevent cross-method leak. See AvoidPrintStackTraceCheck for the same fix.
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
    // Detect: `LOG.error("...", e)` style — call
    // to.error /.warn /.info etc.
    if (n.getScope().isEmpty()) return;
    if (!(n.getScope().get() instanceof FieldAccessExpr fa)) return;
    String logMethod = fa.getNameAsString();
    if (!"error".equals(logMethod)
        && !"warn".equals(logMethod)
        && !"info".equals(logMethod)
        && !"debug".equals(logMethod)) {
      return;
    }
    // Check if any argument is a catch param.
    boolean usesCatchParam =
        n.getArguments().stream()
            .anyMatch(
                a -> a instanceof NameExpr ne && catchParamNames.contains(ne.getNameAsString()));
    if (usesCatchParam) catchParamNames.clear();
  }
}

package org.plumb.style.error;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** catch block throws new exception without including original. */
public final class AvoidLosingExceptionInformationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-lose-exception-info", "LOSE_EXCEPTION_INFO"),
          "catch block throws new exception without including original",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-209"),
          "org.plumb.style.AvoidLosingExceptionInformationCheck",
          true,
          List.of("ast-error-losing-exception-information", "lose-exception-info"));

  /** Catch-param names in the current method. */
  private Set<String> catchParamNames = new HashSet<>();

  @Override
  public String name() {
    return "lose-exception-info";
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
  public void visit(ThrowStmt n, StyleContext ctx) {
    if (n.findAncestor(CatchClause.class).isEmpty()) return;
    if (n.getExpression() == null) return;
    if (!(n.getExpression() instanceof ObjectCreationExpr oc)) return;
    // Check whether the catch param appears as a
    // constructor argument.
    boolean includesCatchParam = false;
    for (Expression arg : oc.getArguments()) {
      if (arg instanceof com.github.javaparser.ast.expr.NameExpr ne
          && catchParamNames.contains(ne.getNameAsString())) {
        includesCatchParam = true;
        break;
      }
    }
    if (includesCatchParam) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "throw new "
            + oc.getType().getNameAsString()
            + "() does not include original exception as cause",
        RuleDef.Severity.MEDIUM,
        "throw");
  }
}

/*
 * catch block parameter should not be reassigned.
 *
 * <p><b>R-818 (2026-10-14): pattern A rewrite.</b> The previous
 * implementation called {@code n.findAll(CatchClause.class)} +
 * for EACH catch {@code cc.findAll(AssignExpr.class)} ? N·M walks
 * per file. The new implementation uses
 * {@code visit(CatchClause)} (dispatched by AstDispatcher once
 * per catch) + a single {@code cc.walk(AssignExpr, ...)} per
 * catch. Total: 1 walk per file (was 1 + N walks).</p>
 */
package org.plumb.style.error;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class AssignmentToCatchBlockParameterCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-assignmenttocatchblockparameter", "ASSIGNMENTTOCATCHBLOCKPARAMETER"),
          "catch block parameter should not be reassigned",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.AssignmentToCatchBlockParameterCheck",
          true,
          List.of("ast-error-assign-catch-var", "assignmenttocatchblockparametercheck")
      );

  @Override
  public String name() {
    return "assignmenttocatchblockparameter";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CatchClause cc, StyleContext ctx) {
    String name = cc.getParameter().getNameAsString();
    cc.walk(
        AssignExpr.class,
        ae -> {
          if (ae.getTarget() instanceof NameExpr ne && ne.getNameAsString().equals(name)) {
            int line = ae.getBegin().map(p -> p.line).orElse(-1);
            int col = ae.getBegin().map(p -> p.column).orElse(-1);
            ctx.report(
                line,
                col,
                DEFAULT_RULE,
                "Catch parameter " + name + " reassigned",
                RuleDef.Severity.MEDIUM,
                name);
          }
        });
  }
}

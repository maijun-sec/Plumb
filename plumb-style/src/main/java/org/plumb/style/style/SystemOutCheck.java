package org.plumb.style.style;

import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Avoid System.out / System.err in production code. */
public final class SystemOutCheck implements AstChecker {

  private StyleContext ctx;
  private RuleDef rule;

  @Override
  public String name() {
    return "system-out";
  }

  @Override
  public void init(StyleContext ctx) {
    this.ctx = ctx;
    this.rule = ctx.rules().byId(RuleRegistry.STYLE_SYSTEM_OUT).orElse(null);
  }

  @Override
  public void visit(MethodCallExpr mce, StyleContext ctx) {
    if (mce.getScope().isPresent() && mce.getScope().get() instanceof FieldAccessExpr fae) {
      if (fae.getScope().toString().equals("System")
          && (fae.getNameAsString().equals("out") || fae.getNameAsString().equals("err"))) {
        int line = mce.getRange().map(r -> r.begin.line).orElse(-1);
        int col = mce.getRange().map(r -> r.begin.column).orElse(-1);
        this.ctx.report(
            line,
            col,
            this.rule,
            "Avoid System.out / System.err in production code (use a logger)",
            RuleDef.Severity.LOW,
            mce.toString());
      }
    }
  }
}

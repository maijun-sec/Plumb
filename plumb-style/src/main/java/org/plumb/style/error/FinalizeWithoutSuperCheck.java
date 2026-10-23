package org.plumb.style.error;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** finalize() should call super.finalize(). */
public final class FinalizeWithoutSuperCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-finalize-without-super", "FINALIZE_WITHOUT_SUPER"),
          "finalize() should call super.finalize()",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-586"),
          "org.plumb.style.FinalizeWithoutSuperCheck",
          true,
          List.of("ast-error-finalize-without-super", "finalize-without-super")
      );

  @Override
  public String name() {
    return "finalize-without-super";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (!"finalize".equals(n.getNameAsString())) return;
    boolean hasSuperFinalize =
        n.findAll(MethodCallExpr.class).stream()
            .anyMatch(
                m ->
                    "finalize".equals(m.getNameAsString())
                        && m.getScope().isPresent()
                        && m.getScope().get() instanceof SuperExpr);
    if (!hasSuperFinalize) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "finalize() should call super.finalize()",
          RuleDef.Severity.MEDIUM,
          "finalize");
    }
  }
}

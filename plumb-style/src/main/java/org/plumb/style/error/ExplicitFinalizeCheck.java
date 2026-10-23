package org.plumb.style.error;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid explicit calls to finalize() (use try-with-resources instead). */
public final class ExplicitFinalizeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-explicit-finalize", "EXPLICIT_FINALIZE"),
          "avoid explicit calls to finalize() (use try-with-resources instead)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-586"),
          "org.plumb.style.ExplicitFinalizeCheck",
          true,
          List.of("ast-error-explicit-finalize", "error-explicit-finalize")
      );

  @Override
  public String name() {
    return "explicit-finalize";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"finalize".equals(n.getNameAsString())) return;
    // Skip the declaration of finalize itself (allowed).
    if (n.getParentNode().isPresent()
        && n.getParentNode().get() instanceof com.github.javaparser.ast.body.MethodDeclaration md
        && "finalize".equals(md.getNameAsString())) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Avoid explicit call to finalize()",
        RuleDef.Severity.MEDIUM,
        "finalize");
  }
}

package org.plumb.style.error;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * finalize() must call super.finalize().
 *
 * <p>Pattern D per-method visit. If method name is {@code "finalize"} and no descendant {@code
 * super.finalize()} call, fire (preserves original behavior for abstract / native methods which
 * lack a body).
 */
public final class SuperFinalizeCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-super-finalize", "SUPER_FINALIZE"),
          "finalize() must call super.finalize()",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-568"),
          "org.plumb.style.SuperFinalizeCheck",
          true,
          List.of("ast-error-finalize", "super-finalize"));

  @Override
  public String name() {
    return "super-finalize";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!m.getNameAsString().equals("finalize")) return;
    boolean[] hasSuper = {false};
    if (m.getBody().isPresent()) {
      m.getBody()
          .get()
          .walk(
              Node.class,
              n -> {
                if (n instanceof MethodCallExpr) {
                  MethodCallExpr mc = (MethodCallExpr) n;
                  if (mc.getScope().isPresent()
                      && mc.getScope().get().toString().equals("super")
                      && mc.getNameAsString().equals("finalize")) {
                    hasSuper[0] = true;
                  }
                }
              });
    }
    if (!hasSuper[0]) {
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
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

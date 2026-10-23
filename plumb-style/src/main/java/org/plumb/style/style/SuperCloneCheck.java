package org.plumb.style.style;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * clone() must call super.clone().
 *
 * <p>Pattern D R-818: per-method visit. If method name is {@code "clone"} and no descendant {@code
 * super.clone()} call, fire.
 */
public final class SuperCloneCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-super-clone", "SUPER_CLONE"),
          "clone() must call super.clone()",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-668"),
          "org.plumb.style.SuperCloneCheck",
          true,
          List.of("ast-style-clone", "super-clone")
      );

  @Override
  public String name() {
    return "super-clone";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!m.getNameAsString().equals("clone")) return;
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
                      && mc.getNameAsString().equals("clone")) {
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
          "clone() should call super.clone()",
          RuleDef.Severity.MEDIUM,
          "clone");
    }
  }
}

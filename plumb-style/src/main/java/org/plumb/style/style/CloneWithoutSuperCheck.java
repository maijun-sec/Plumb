package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** clone() should call super.clone(). */
public final class CloneWithoutSuperCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-clone-without-super", "CLONE_WITHOUT_SUPER"),
          "clone() should call super.clone()",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-664"),
          "org.plumb.style.CloneWithoutSuperCheck",
          true,
          List.of("ast-style-idiom", "clone-without-super"));

  @Override
  public String name() {
    return "clone-without-super";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (!"clone".equals(n.getNameAsString())) return;
    BlockStmt body = n.getBody().orElse(null);
    if (body == null) return;
    boolean hasSuperClone =
        body.findAll(MethodCallExpr.class).stream()
            .anyMatch(
                m ->
                    "clone".equals(m.getNameAsString())
                        && m.getScope().isPresent()
                        && m.getScope().get() instanceof SuperExpr);
    if (!hasSuperClone) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "clone() override should call super.clone()",
          RuleDef.Severity.HIGH,
          "clone");
    }
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** equals() but no hashCode() : broken contract (BC_EQUALS_NO_HASHCODE). */
public final class BcEqualsNoHashcodeCallCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-bc-equals-no-hash", "BC_EQUALS_NO_HASH"),
          "equals() but no hashCode() : broken contract (BC_EQUALS_NO_HASHCODE)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-697"),
          "org.plumb.style.BcEqualsNoHashcodeCallCheck",
          true,
          List.of("ast-style-equals-no-hashcode", "bc-equals-no-hash"));

  @Override
  public String name() {
    return "bc-equals-no-hash";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (!"equals".equals(n.getNameAsString())) return;
    if (n.getBody().isEmpty()) return;
    // Look for any reference to hashCode() in the body.
    for (MethodCallExpr mc : n.getBody().get().findAll(MethodCallExpr.class)) {
      if ("hashCode".equals(mc.getNameAsString())) return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class has equals() but no hashCode() : " + "HashMap / HashSet will misbehave",
        RuleDef.Severity.MEDIUM,
        "equals");
  }
}

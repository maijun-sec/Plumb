package org.plumb.style.bug;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** too many same-typed parameters : use a parameter object. */
public final class MethodSignatureCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-method-signature", "METHOD_SIGNATURE"),
          "too many same-typed parameters : use a parameter object",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MethodSignatureCheck",
          true,
          List.of("ast-bug-method-signature", "method-signature")
      );

  @Override
  public String name() {
    return "method-signature";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    java.util.Map<String, Integer> typeCount = new java.util.HashMap<>();
    for (Parameter p : n.getParameters()) {
      String t = p.getType().asString();
      typeCount.merge(t, 1, Integer::sum);
    }
    for (java.util.Map.Entry<String, Integer> e : typeCount.entrySet()) {
      if (e.getValue() >= 3) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Method '"
                + n.getNameAsString()
                + "' has "
                + e.getValue()
                + " parameters of type '"
                + e.getKey()
                + "'",
            RuleDef.Severity.LOW,
            e.getKey());
      }
    }
  }
}

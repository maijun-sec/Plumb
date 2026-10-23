package org.plumb.style.naming;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** method name is too short. */
public final class ShortMethodNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-short-method", "SHORT_METHOD"),
          "method name is too short",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.ShortMethodNameCheck",
          true,
          List.of("ast-naming-method-name-short", "short-method")
      );

  @Override
  public String name() {
    return "short-method";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getNameAsString().length() >= 2) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Method name '" + n.getNameAsString() + "' is too short",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}

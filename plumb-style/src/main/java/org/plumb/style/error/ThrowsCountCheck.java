package org.plumb.style.error;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Method declares too many checked exceptions (default threshold 4). */
public final class ThrowsCountCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-throws-count", "THROWS_COUNT"),
          "method should declare at most N checked exceptions (default 4)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ThrowsCountCheck",
          true,
          List.of("ast-error-throws-count", "throws-count"));

  @Override
  public String name() {
    return "throws-count";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    int nthrows = m.getThrownExceptions().size();
    if (nthrows <= 4) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Method "
            + m.getNameAsString()
            + " declares "
            + nthrows
            + " checked exceptions; consider wrapping or splitting",
        RuleDef.Severity.LOW,
        m.getNameAsString());
  }
}

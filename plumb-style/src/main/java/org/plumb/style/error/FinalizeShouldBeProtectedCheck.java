package org.plumb.style.error;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** finalize() should be protected. */
public final class FinalizeShouldBeProtectedCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-finalize-protected", "FINALIZE_PROTECTED"),
          "finalize() should be protected",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.FinalizeShouldBeProtectedCheck",
          true,
          List.of("ast-error-finalize-should-be-protected", "finalize-protected")
      );

  @Override
  public String name() {
    return "finalize-protected";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (!"finalize".equals(n.getNameAsString())) return;
    boolean isProtected =
        n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.PROTECTED);
    if (isProtected) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "finalize() should be protected",
        RuleDef.Severity.LOW,
        "finalize");
  }
}

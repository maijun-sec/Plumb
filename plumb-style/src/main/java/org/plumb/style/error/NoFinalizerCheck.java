package org.plumb.style.error;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Do not override {@code finalize()} ? deprecated in Java 9+. */
public final class NoFinalizerCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-no-finalizer", "NO_FINALIZER"),
          "do not override finalize()",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NoFinalizerCheck",
          true,
          List.of("ast-error-finalizer-no", "no-finalizer"));

  @Override
  public String name() {
    return "no-finalizer";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!"finalize".equals(m.getNameAsString())) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Avoid finalize() (deprecated in Java 9+)",
        RuleDef.Severity.MEDIUM,
        "finalize");
  }
}

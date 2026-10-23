package org.plumb.style.performance;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** No-arg empty constructor duplicates the default. */
public final class UnnecessaryConstructorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-unnecessary-constructor", "UNNECESSARY_CONSTRUCTOR"),
          "No-arg empty constructor duplicates the default",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnnecessaryConstructorCheck",
          true,
          List.of("ast-performance-unnecessary-constructor", "unnecessary-constructor")
      );

  @Override
  public String name() {
    return "unnecessary-constructor";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ConstructorDeclaration n, StyleContext ctx) {
    if (!n.getParameters().isEmpty()) return;
    if (!n.getBody().getStatements().isEmpty()) return;
    if (!n.getAnnotations().isEmpty()) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Empty no-arg constructor in '" + n.getNameAsString() + "' duplicates the default",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}

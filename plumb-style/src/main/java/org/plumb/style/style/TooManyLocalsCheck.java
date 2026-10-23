package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** method declares more than 15 locals. */
public final class TooManyLocalsCheck implements AstChecker {

  private static final int DEFAULT_THRESHOLD = 15;

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-too-many-locals", "TOO_MANY_LOCALS"),
          "method declares more than " + DEFAULT_THRESHOLD + " locals",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.TooManyLocalsCheck",
          true,
          List.of("ast-style-many-locals", "too-many-locals")
      );

  @Override
  public String name() {
    return "too-many-locals";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    long localCount =
        n.findAll(com.github.javaparser.ast.expr.VariableDeclarationExpr.class).stream().count();
    if (localCount > DEFAULT_THRESHOLD) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Method "
              + n.getNameAsString()
              + " has "
              + localCount
              + " local declarations (>"
              + DEFAULT_THRESHOLD
              + ")",
          RuleDef.Severity.LOW,
          n.getNameAsString());
    }
  }
}

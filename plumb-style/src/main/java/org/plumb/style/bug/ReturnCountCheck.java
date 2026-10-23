package org.plumb.style.bug;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * method should have a single return (heuristic).
 *
 * <p>Pattern D R-818: per-method visit. If method body has more than one {@link ReturnStmt}
 * descendant, fire.
 */
public final class ReturnCountCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-return-count", "RETURN_COUNT"),
          "method should have a single return (heuristic)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ReturnCountCheck",
          true,
          List.of("ast-bug-return-count", "return-count")
      );

  @Override
  public String name() {
    return "return-count";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!m.getBody().isPresent()) return;
    // Lesson 1277: int[] wrapper (lambda can't capture mutable int).
    int[] returns = {0};
    m.getBody()
        .get()
        .walk(
            Node.class,
            n -> {
              if (n instanceof ReturnStmt) returns[0]++;
            });
    if (returns[0] > 1) {
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Method "
              + m.getNameAsString()
              + " has "
              + returns[0]
              + " return statements; consider single exit",
          RuleDef.Severity.LOW,
          m.getNameAsString());
    }
  }
}

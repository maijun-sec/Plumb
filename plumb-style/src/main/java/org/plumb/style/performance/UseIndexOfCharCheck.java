package org.plumb.style.performance;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use indexOf(char) instead of indexOf(String) for single chars. */
public final class UseIndexOfCharCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-use-indexof-char", "USE_INDEXOF_CHAR"),
          "use indexOf(char) instead of indexOf(String) for single chars",
          RuleDef.Severity.LOW,
          RuleDef.Type.PERFORMANCE,
          List.of(),
          "org.plumb.style.UseIndexOfCharCheck",
          true,
          List.of("ast-performance-indexof-char", "use-indexof-char"));

  @Override
  public String name() {
    return "use-indexof-char";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"indexOf".equals(n.getNameAsString())) return;
    if (n.getArguments().size() != 1) return;
    if (!(n.getArgument(0) instanceof StringLiteralExpr s)) return;
    if (s.getValue().length() != 1) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use indexOf(char) instead of indexOf(String) : avoid String allocation",
        RuleDef.Severity.LOW,
        "indexOf");
  }
}

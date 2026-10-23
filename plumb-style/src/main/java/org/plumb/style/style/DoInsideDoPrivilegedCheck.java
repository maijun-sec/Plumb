package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Action should be inside {@code doPrivileged}. */
public final class DoInsideDoPrivilegedCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-do-inside-do-privileged", "DO_INSIDE_DO_PRIVILEGED"),
          "Action should be inside doPrivileged",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DoInsideDoPrivilegedCheck",
          true,
          List.of("ast-style-inside-do-privileged", "do-inside-do-privileged")
      );

  @Override
  public String name() {
    return "do-inside-do-privileged";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    String n = m.getNameAsString();
    if (!"readFile".equals(n) && !"writeFile".equals(n)) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Action should be inside doPrivileged",
        RuleDef.Severity.MEDIUM,
        m.getNameAsString());
  }
}
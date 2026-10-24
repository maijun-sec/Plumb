package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid new Date() : use Calendar.getInstance() or LocalDate.now(). */
public final class AvoidCalendarDateInstanceCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(
              "ast-performance-avoid-calendar-date-instance", "AVOID_CALENDAR_DATE_INSTANCE"),
          "avoid new Date() : use Calendar.getInstance() or LocalDate.now()",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.AvoidCalendarDateInstanceCheck",
          true,
          List.of("ast-performance-calendar-date-instance", "avoid-calendar-date-instance"));

  @Override
  public String name() {
    return "avoid-calendar-date-instance";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    if (!"Date".equals(n.getType().getNameAsString())) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Avoid new Date() : use Calendar.getInstance() or LocalDate.now()",
        RuleDef.Severity.LOW,
        "Date");
  }
}

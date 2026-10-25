package org.plumb.style.style;

import com.github.javaparser.ast.expr.AssignExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Questionable boolean assignment. */
public final class QuestionableBooleanAssignmentCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-questionablebooleanassignment", "QUESTIONABLEBOOLEANASSIGNMENT"),
          "Questionable boolean assignment",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.QuestionableBooleanAssignmentCheck",
          true,
          List.of("ast-style-questionable-boolean-assign", "questionablebooleanassignment"));

  @Override
  public String name() {
    return "questionablebooleanassignment";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(AssignExpr a, StyleContext ctx) {
    String s = a.getValue().toString();
    if (s.equals("true") || s.equals("false")) {
      int line = a.getBegin().map(p -> p.line).orElse(-1);
      int col = a.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "Questionable boolean assignment", RuleDef.Severity.LOW, s);
    }
  }
}

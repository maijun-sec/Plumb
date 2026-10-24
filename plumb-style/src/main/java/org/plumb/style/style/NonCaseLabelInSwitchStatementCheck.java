package org.plumb.style.style;

import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** labeled statement in switch : use case label. */
public final class NonCaseLabelInSwitchStatementCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-non-case-label", "NON_CASE_LABEL"),
          "labeled statement in switch : use case label",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.NonCaseLabelInSwitchStatementCheck",
          true,
          List.of("ast-style-case-label-in-switch-statement", "non-case-label"));

  @Override
  public String name() {
    return "non-case-label";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SwitchEntry n, StyleContext ctx) {
    for (Statement s : n.getStatements()) {
      if (s instanceof LabeledStmt) {
        int line = s.getBegin().map(p -> p.line).orElse(-1);
        int col = s.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Labeled statement in switch : use a case label",
            RuleDef.Severity.MEDIUM,
            "label");
      }
    }
  }
}

package org.plumb.style.style;

import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** switch case body exceeds 5 statements. */
public final class SwitchDensityCheck implements AstChecker {

  private static final int DEFAULT_THRESHOLD = 5;

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-switch-density", "SWITCH_DENSITY"),
          "switch case body exceeds " + DEFAULT_THRESHOLD + " statements",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SwitchDensityCheck",
          true,
          List.of("ast-style-switch-density", "switch-density"));

  @Override
  public String name() {
    return "switch-density";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SwitchStmt n, StyleContext ctx) {
    for (SwitchEntry e : n.getEntries()) {
      int count = e.getStatements().size();
      if (count > DEFAULT_THRESHOLD) {
        int line = e.getBegin().map(p -> p.line).orElse(-1);
        int col = e.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Switch case has "
                + count
                + " statements (>"
                + DEFAULT_THRESHOLD
                + "); extract a method",
            RuleDef.Severity.LOW,
            "switch-case");
      }
    }
  }
}

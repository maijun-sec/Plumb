package org.plumb.style.style;

import com.github.javaparser.ast.stmt.SynchronizedStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Malformed synchronization. */
public final class MalformedSyncCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-malformedsync", "MALFORMEDSYNC"),
          "Malformed synchronization",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MalformedSyncCheck",
          true,
          List.of("ast-style-malformed-sync", "malformedsync"));

  @Override
  public String name() {
    return "malformedsync";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SynchronizedStmt s, StyleContext ctx) {
    String lock = s.getExpression().toString();
    if (lock.equals("this") || lock.contains("getClass()")) {
      int line = s.getBegin().map(p -> p.line).orElse(-1);
      int col = s.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Malformed synchronization: " + lock,
          RuleDef.Severity.MEDIUM,
          lock);
    }
  }
}

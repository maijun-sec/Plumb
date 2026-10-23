package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid volatile (use proper synchronization). */
public final class AvoidUsingVolatileCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-avoid-volatile", "AVOID_VOLATILE"),
          "avoid volatile (use proper synchronization)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.AvoidUsingVolatileCheck",
          true,
          List.of("ast-style-volatile", "avoid-volatile")
      );

  @Override
  public String name() {
    return "avoid-volatile";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    for (Modifier m : n.getModifiers()) {
      if (m.getKeyword() == Modifier.Keyword.VOLATILE) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Avoid volatile : use proper synchronization (Atomic*, synchronized, Lock)",
            RuleDef.Severity.LOW,
            "volatile");
        return;
      }
    }
  }
}

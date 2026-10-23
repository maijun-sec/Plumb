package org.plumb.style.style;

import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid System.out.println : use a logger. */
public final class SystemPrintlnCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-system-println", "SYSTEM_PRINTLN"),
          "avoid System.out.println : use a logger",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.SystemPrintlnCheck",
          false,
          List.of("ast-style-system-println", "system-println")
      ); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832 baseline)

  @Override
  public String name() {
    return "system-println";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"println".equals(n.getNameAsString()) && !"print".equals(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    if (!(n.getScope().get() instanceof FieldAccessExpr fa)) return;
    String owner = fa.getScope().toString();
    String field = fa.getNameAsString();
    if ("System".equals(owner) && "out".equals(field)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Avoid System.out.println : use a logger",
          RuleDef.Severity.MEDIUM,
          "println");
    }
  }
}

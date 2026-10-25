package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** System.out.printf/System.err.printf. */
public final class PrintfFailCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-printf-fail", "PRINTF_FAIL"),
          "System.out.printf/System.err.printf",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.PrintfFailCheck",
          true,
          List.of("ast-style-printf-fail", "printf-fail"));

  @Override
  public String name() {
    return "printf-fail";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr mc, StyleContext ctx) {
    if (mc.getNameAsString().equals("printf")
        && mc.getScope().isPresent()
        && mc.getScope().get().toString().startsWith("System.")) {
      int line = mc.getBegin().map(p -> p.line).orElse(-1);
      int col = mc.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Use a logger instead of " + mc.getScope().get() + ".printf",
          RuleDef.Severity.LOW,
          "printf");
    }
  }
}

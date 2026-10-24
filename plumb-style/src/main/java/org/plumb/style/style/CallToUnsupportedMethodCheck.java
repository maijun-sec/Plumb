package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Call to unsupported method (method name starting with {@code internal}). */
public final class CallToUnsupportedMethodCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-call-to-unsupported-method", "CALL_TO_UNSUPPORTED_METHOD"),
          "Call to unsupported method",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CallToUnsupportedMethodCheck",
          true,
          List.of("ast-style-to-unsupported-method", "call-to-unsupported-method"));

  @Override
  public String name() {
    return "call-to-unsupported-method";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    if (!m.getNameAsString().startsWith("internal")) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Call to unsupported method",
        RuleDef.Severity.HIGH,
        m.getNameAsString());
  }
}

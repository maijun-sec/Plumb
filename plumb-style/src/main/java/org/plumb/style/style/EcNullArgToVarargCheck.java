package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** null passed as varargs (EC_NULL_ARG_TO_VARARG_METHOD). */
public final class EcNullArgToVarargCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-ec-null-vararg", "EC_NULL_VARARG"),
          "null passed as varargs (EC_NULL_ARG_TO_VARARG_METHOD)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-754"),
          "org.plumb.style.EcNullArgToVarargCheck",
          true,
          List.of("ast-style-null-arg-to-vararg-method", "ec-null-vararg")
      );

  @Override
  public String name() {
    return "ec-null-vararg";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (n.getArguments().isEmpty()) return;
    // If the LAST argument is exactly `null` and
    // the method's last parameter is varargs,
    // the call is ambiguous.
    var last = n.getArgument(n.getArguments().size() - 1);
    if (!(last instanceof NullLiteralExpr)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "null passed to a varargs method : cast to (T[]) null",
        RuleDef.Severity.MEDIUM,
        "null");
  }
}

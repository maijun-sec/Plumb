package org.plumb.style.error;

import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** throw in finally : swallows original exception. */
public final class ThrowInFinallyCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-throw-in-finally", "THROW_IN_FINALLY"),
          "throw in finally : swallows original exception",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-584"),
          "org.plumb.style.ThrowInFinallyCheck",
          true,
          List.of("ast-error-in-finally", "throw-in-finally"));

  @Override
  public String name() {
    return "throw-in-finally";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ThrowStmt n, StyleContext ctx) {
    if (!isInFinally(n)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "throw in finally block : swallows original exception",
        RuleDef.Severity.HIGH,
        "throw");
  }

  private static boolean isInFinally(com.github.javaparser.ast.Node n) {
    var cur = n.getParentNode().orElse(null);
    while (cur != null) {
      if (cur instanceof TryStmt t) {
        return t.getFinallyBlock().isPresent() && t.getFinallyBlock().get().containsWithin(n);
      }
      cur = cur.getParentNode().orElse(null);
    }
    return false;
  }
}

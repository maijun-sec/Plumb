package org.plumb.style.style;

import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** return in finally block silently swallows exceptions. */
public final class ReturnFromFinallyBlockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-return-from-finally", "RETURN_FROM_FINALLY"),
          "return in finally block silently swallows exceptions",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-584"),
          "org.plumb.style.ReturnFromFinallyBlockCheck",
          true,
          List.of("ast-style-return-from-finally", "return-from-finally"));

  @Override
  public String name() {
    return "return-from-finally";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ReturnStmt n, StyleContext ctx) {
    if (n.findAncestor(TryStmt.class).isEmpty()) return;
    // Check whether we're inside a finally block.
    if (isInFinally(n)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Avoid return in finally : it silently swallows exceptions",
          RuleDef.Severity.HIGH,
          "return");
    }
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

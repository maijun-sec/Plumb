package org.plumb.style.style;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** synchronized on a local variable (NP_SYNC_INVOKED_ON_UNSHARED_VARIABLE). */
public final class NpSyncOnUnsharedVarCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-np-sync-unshared", "NP_SYNC_UNSHARED"),
          "synchronized on a local variable (NP_SYNC_INVOKED_ON_UNSHARED_VARIABLE)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-820"),
          "org.plumb.style.NpSyncOnUnsharedVarCheck",
          true,
          List.of("ast-style-sync-invoked-on-unshared-variable", "np-sync-unshared"));

  @Override
  public String name() {
    return "np-sync-unshared";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SynchronizedStmt n, StyleContext ctx) {
    Expression lock = n.getExpression();
    // Skip: this, class lock.
    if (lock instanceof NameExpr ne) {
      String name = ne.getNameAsString();
      if ("this".equals(name)) return;
      if (Character.isUpperCase(name.charAt(0))) return; // class
      // A simple-typed local : likely a
      // newly-allocated local. Flag it.
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "synchronized on a local variable : the lock " + "is on a per-call object",
          RuleDef.Severity.MEDIUM,
          name);
    }
  }
}

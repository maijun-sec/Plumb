package org.plumb.style.bug;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** loading a value known to be null (NP_LOAD_OF_KNOWN_NULL_VALUE). */
public final class NpLoadOfKnownNullCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-np-load-null", "NP_LOAD_NULL"),
          "loading a value known to be null (NP_LOAD_OF_KNOWN_NULL_VALUE)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-476"),
          "org.plumb.style.NpLoadOfKnownNullCheck",
          true,
          List.of("ast-bug-load-of-known-null-value", "np-load-null"));

  @Override
  public String name() {
    return "np-load-null";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (n.getScope().isEmpty()) return;
    // Heuristic: the receiver is a method call
    // whose name ends with "OrNull" or starts
    // with "find" / "peek" / "poll".
    if (!(n.getScope().get() instanceof MethodCallExpr)) return;
    MethodCallExpr receiver = (MethodCallExpr) n.getScope().get();
    String name = receiver.getNameAsString();
    if (name.endsWith("OrNull")
        || name.startsWith("find")
        || name.startsWith("peek")
        || name.startsWith("poll")) {
      // A chained call that doesn't null-check.
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Method '" + name + "' can return null : " + "add a null check before chaining",
          RuleDef.Severity.MEDIUM,
          name);
    }
  }
}

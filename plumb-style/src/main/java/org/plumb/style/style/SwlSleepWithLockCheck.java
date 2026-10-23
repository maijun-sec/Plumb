package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Thread.sleep inside synchronized (SWL_SLEEP_WITH_LOCK_HELD). */
public final class SwlSleepWithLockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-swl-sleep-lock", "SWL_SLEEP_LOCK"),
          "Thread.sleep inside synchronized (SWL_SLEEP_WITH_LOCK_HELD)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-820"),
          "org.plumb.style.SwlSleepWithLockCheck",
          true,
          List.of("ast-style-sleep-with-lock-held", "swl-sleep-lock")
      );

  @Override
  public String name() {
    return "swl-sleep-lock";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"sleep".equals(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    String scope = n.getScope().get().toString();
    if (!scope.endsWith("Thread") && !"currentThread".equals(scope)) return;
    // Walk up to see if we're inside a synchronized block.
    com.github.javaparser.ast.Node parent = n.getParentNode().orElse(null);
    while (parent != null
        && !(parent instanceof SynchronizedStmt)
        && !(parent instanceof com.github.javaparser.ast.body.MethodDeclaration)
        && !(parent instanceof com.github.javaparser.ast.body.ConstructorDeclaration)) {
      parent = parent.getParentNode().orElse(null);
    }
    boolean inSync = parent instanceof SynchronizedStmt;
    if (!inSync && parent instanceof com.github.javaparser.ast.body.MethodDeclaration) {
      com.github.javaparser.ast.body.MethodDeclaration md =
          (com.github.javaparser.ast.body.MethodDeclaration) parent;
      inSync =
          md.getModifiers().stream()
              .anyMatch(
                  m -> m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.SYNCHRONIZED);
    }
    if (inSync) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Thread.sleep inside synchronized : releases the lock for " + "its duration",
          RuleDef.Severity.MEDIUM,
          "sleep");
    }
  }
}

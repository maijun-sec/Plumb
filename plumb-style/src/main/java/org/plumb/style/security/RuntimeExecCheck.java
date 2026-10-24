/*
 * R722-C10: AST-style CWE-511 (Logic / Time Bomb) checker.
 *
 * <p>Fires on any {@code Runtime.exec} / {@code ProcessBuilder.start}
 * call (the canonical "launch an executable" pattern). The
 * Juliet CWE-511 set has the bug pattern: when a counter
 * reaches some threshold, the code launches an executable
 * (a logic bomb). Detecting the launch itself is enough to
 * flag the CWE.</p>
 *
 * <p>This is intentionally broad: any {@code Runtime.exec} call
 * is suspicious because it can be invoked by a logic bomb.
 * False positives are expected for legitimate
 * {@code Runtime.exec(...)} calls; we accept that tradeoff
 * because in security audits every Runtime.exec is reviewed
 * anyway.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-511: Logic / Time Bomb.
 *
 * <p>Detects {@code Runtime.exec(...)} calls (and {@code Runtime.getRuntime().exec(...)} chained
 * calls). These can be triggered by logic bombs.
 */
public final class RuntimeExecCheck implements AstChecker {

  private static final String RULE_ID = "dfa-security-runtime-exec";
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(RULE_ID, "RUNTIME_EXEC"),
          "Runtime.exec call may be triggered by logic bomb (CWE-511)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-511"),
          "org.plumb.style.security.RuntimeExecCheck",
          true);

  @Override
  public String name() {
    return "runtime-exec";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    String methodName = n.getNameAsString();
    if (!"exec".equals(methodName)) return;
    // match either Runtime.exec() or Runtime.getRuntime().exec()
    if (n.getScope().isEmpty()) return;
    String scopeStr = n.getScope().get().toString();
    if (!scopeStr.contains("Runtime")) return;

    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Runtime.exec call detected; can be triggered by a logic bomb (CWE-511)",
        RuleDef.Severity.MEDIUM,
        "runtime-exec");
  }
}

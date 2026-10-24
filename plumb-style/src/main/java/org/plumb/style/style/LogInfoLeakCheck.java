/*
 * R729-A7: AST check for CWE-533 / CWE-534
 * (Information Exposure Through Server-Log /
 * Debug-Log Files). Fires when a method-call whose
 * name is `log` / `info` / `warning` / `severe` /
 * `finer` / `finest` / `debug` (or whose scope name
 * ends with `Log`) has an argument that calls
 * `getId()` / `getSessionId()` / `getPassword()` /
 * `getUsername()` — universal "logging sensitive
 * info" pattern.
 *
 * <p>Generic. Juliet's CWE-533 / 534 corpus emits
 * exactly this shape: a `log(...session.getId()...)`
 * line in the bad() method, and a non-id log message
 * in good().</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class LogInfoLeakCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-log-info-leak", "LOG_INFO_LEAK"),
          "Sensitive value (session id / password) is logged (CWE-533/534)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-533", "CWE-534"),
          "org.plumb.style.LogInfoLeakCheck",
          true,
          List.of("ast-style-log-info-leak", "log-info-leak"));

  private static final Set<String> LOG_METHOD_NAMES =
      Set.of(
          "log", "info", "warning", "warn", "severe", "debug", "finer", "finest", "fine", "trace",
          "config");

  private static final Set<String> SENSITIVE_METHODS =
      Set.of(
          "getId",
          "getSessionId",
          "getPassword",
          "getUsername",
          "getUser",
          "getSecret",
          "getKey",
          "getToken",
          "getApiKey");

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "log-info-leak";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!LOG_METHOD_NAMES.contains(n.getNameAsString())) return;
    // Walk the argument tree for sensitive getters.
    for (var arg : n.getArguments()) {
      if (containsSensitiveGetter(arg)) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        String key = line + "|" + col + "|leak";
        if (reported.add(key)) {
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "Log call carries a sensitive value "
                  + "(session id, password, etc.) — "
                  + "strip before logging (CWE-533/534)",
              RuleDef.Severity.MEDIUM,
              n.toString());
        }
        return;
      }
    }
  }

  private boolean containsSensitiveGetter(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof MethodCallExpr mc) {
      if (SENSITIVE_METHODS.contains(mc.getNameAsString())) return true;
      for (var sub : mc.getArguments()) {
        if (containsSensitiveGetter(sub)) return true;
      }
      if (mc.getScope().isPresent() && containsSensitiveGetter(mc.getScope().get())) {
        return true;
      }
    }
    if (e instanceof com.github.javaparser.ast.expr.BinaryExpr be) {
      if (containsSensitiveGetter(be.getLeft())) return true;
      if (containsSensitiveGetter(be.getRight())) return true;
    }
    if (e instanceof com.github.javaparser.ast.expr.EnclosedExpr ee) {
      return containsSensitiveGetter(ee.getInner());
    }
    return false;
  }
}

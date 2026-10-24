/*
 * R722-C9: AST-style CWE-535 (Information Exposure Through
 * Shell Error) checker.
 *
 * <p>Fires when a write to {@link System#err} / an
 * {@code OutputStreamWriter}-wrapped {@link System#err}
 * carries a likely-sensitive data value (e.g. session ID,
 * password, cookie value, token). The Juliet CWE-535 set
 * has the bad pattern:
 * <pre>
 *     writerOutputStream = new OutputStreamWriter(System.err, "UTF-8");
 *     writerOutputStream.write(getSessionId());
 * </pre>
 * We fire on any Writer-scope {@code print / println / write / format}
 * call whose argument contains a sensitive keyword.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-535: Information Exposure Through Shell Error.
 *
 * <p>Detects {@code System.err.println / .print / .write(...)} calls (and {@code
 * OutputStreamWriter}-wrapped variants) whose argument string contains a sensitive keyword
 * (session-id, password, cookie, token, secret).
 */
public final class ShellErrorExposureCheck implements AstChecker {

  private static final String RULE_ID = "dfa-security-shell-error-exposure";
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(RULE_ID, "SHELL_ERROR_EXPOSURE"),
          "Sensitive value may be exposed via System.err or wrapped Writer (CWE-535)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-535"),
          "org.plumb.style.security.ShellErrorExposureCheck",
          true);

  private static final Set<String> WRITE_METHODS =
      new LinkedHashSet<>(List.of("print", "println", "write", "format", "printf", "append"));
  private static final Set<String> SENSITIVE_KEYWORDS =
      new LinkedHashSet<>(
          List.of(
              "sessionid",
              "session",
              "password",
              "secret",
              "cookie",
              "token",
              "apikey",
              "credentials",
              "privatekey"));

  @Override
  public String name() {
    return "shell-error-exposure";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    String methodName = n.getNameAsString();
    if (!WRITE_METHODS.contains(methodName)) return;
    if (n.getArguments().isEmpty()) return;
    // Scope should be a writer / System.err / OutputStreamWriter / PrintWriter etc.
    String scopeStr;
    if (n.getScope().isPresent()) {
      scopeStr = n.getScope().get().toString().toLowerCase(Locale.ROOT);
    } else {
      scopeStr = "";
    }
    // Heuristic: fire if scope hints at a writer / err
    boolean looksLikeErrWrite =
        scopeStr.contains("writer")
            || scopeStr.contains("print")
            || scopeStr.contains("system.err")
            || scopeStr.contains("system.out")
            || scopeStr.contains("outputstream")
            || scopeStr.contains("buffered")
            || scopeStr.contains("stream");
    if (!looksLikeErrWrite) return;

    String argStr = n.getArguments().get(0).toString().toLowerCase(Locale.ROOT);
    for (String kw : SENSITIVE_KEYWORDS) {
      if (argStr.contains(kw)) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Sensitive value '" + kw + "' may be exposed via writer call",
            RuleDef.Severity.MEDIUM,
            "shell-error-exposure: " + kw);
        return;
      }
    }
  }
}

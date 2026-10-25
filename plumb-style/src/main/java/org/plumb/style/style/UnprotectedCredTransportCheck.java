package org.plumb.style.style;

import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-523 (Unprotected Transport of Credentials).
 *
 * <p>Detects a servlet-emitting string literal that contains a non-SSL {@code http://} URL AND a
 * login form field ({@code j_security_check} / {@code j_username} / {@code j_password}) — the
 * credentials will be POSTed in cleartext over the non-SSL channel.
 *
 * <p>The {@code (?<!s)http://} pattern tolerates the {@code https://} form, so a form whose action
 * URL is already TLS is not reported.
 */
public final class UnprotectedCredTransportCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unprotected-cred-transport", "UNPROTECTED_CRED_TRANSPORT"),
          "Login form emitted over a non-SSL (http://) URL (CWE-523).",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-523"),
          "org.plumb.style.UnprotectedCredTransportCheck",
          true,
          List.of("ast-style-unprotected-cred-transport-style", "unprotected-cred-transport"));

  private static final Pattern HTTP_URL = Pattern.compile("(?<!s)http://");
  private static final Pattern LOGIN_FORM =
      Pattern.compile("(?i)j_security_check|j_username|j_password");

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "unprotected-cred-transport";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void visit(StringLiteralExpr s, StyleContext ctx) {
    String value = s.getValue();
    if (value == null) return;
    if (!HTTP_URL.matcher(value).find()) return;
    if (!LOGIN_FORM.matcher(value).find()) return;
    int line = s.getBegin().map(p -> p.line).orElse(-1);
    String key = line + "|" + value.hashCode();
    if (!reported.add(key)) return;
    ctx.report(
        line,
        -1,
        DEFAULT_RULE,
        "Login form emitted over non-SSL URL at line "
            + line
            + ". The credentials (j_username / j_password) will be"
            + " transmitted in cleartext (CWE-523).",
        RuleDef.Severity.MEDIUM,
        "unprotected-cred-transport@line " + line);
  }
}

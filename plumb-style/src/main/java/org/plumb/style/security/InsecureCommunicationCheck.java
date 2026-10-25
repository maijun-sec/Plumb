/*
 * ? InsecureCommunicationCheck (CWE-319) migrated from
 * plumb-dfa/.../InsecureCommunicationChecker.java to
 * plumb-style AST.
 *
 * <p>Flags construction of {@code java.net.URL} /
 * {@code java.net.URI} (and the {@code openConnection}
 * entry on {@code URL}) when the protocol is on the
 * cleartext allow-list. The full list lives in
 * {@link #CLEAR_TEXT_PROTOCOLS}; everything else
 * ({@code https}, {@code file}, custom schemes) is
 * fine.</p>
 *
 * <p>The AST version is a strict subset of the previous
 * DFA half: only literal-string arguments fire. The DFA
 * half additionally walked a single MOVE to recover
 * {@code String url = "http://..."; new URL(url);}. The
 * AST half misses that case ? re-enable the DFA half if
 * the DFA coverage gap matters; otherwise the AST half
 * is the canonical implementation.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class InsecureCommunicationCheck implements AstChecker {

  private static final Set<String> CLEAR_TEXT_PROTOCOLS =
      Set.of("http", "ftp", "gopher", "telnet", "ldap", "tftp");

  private static final Set<String> URL_TYPES = Set.of("URL", "URI");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-insecure-communication", "INSECURE_COMMUNICATION"),
          "Communication over a cleartext channel (CWE-319)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-319"),
          "org.plumb.style.security.InsecureCommunicationCheck",
          true,
          List.of("dfa-security-cleartext-transmission", "insecure-communication"));

  @Override
  public String name() {
    return "insecure-communication";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    String typeName = n.getType().getNameAsString();
    if (!URL_TYPES.contains(typeName)) return;
    if (n.getArguments().isEmpty()) return;
    if (!(n.getArgument(0) instanceof StringLiteralExpr s)) return;
    String value = s.asString();
    // value looks like "http://...", "ftp://...", etc.
    int colon = value.indexOf(':');
    if (colon <= 0) return;
    String scheme = value.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
    if (!CLEAR_TEXT_PROTOCOLS.contains(scheme)) return;
    ctx.report(
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Communication over a cleartext channel: "
            + typeName
            + "(\""
            + value
            + "\") — use HTTPS/TLS instead (CWE-319)",
        RuleDef.Severity.MEDIUM,
        n.toString());
  }
}

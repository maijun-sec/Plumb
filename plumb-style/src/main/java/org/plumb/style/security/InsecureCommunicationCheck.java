/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
/*
 * Flags insecure network communication: data sent over a channel that is
 * not encrypted (CWE-319).
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
 * AST half misses that case -- re-enable the DFA half if
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

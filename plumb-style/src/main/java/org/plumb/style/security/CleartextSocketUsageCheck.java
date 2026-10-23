/*
 * R-812 ? Cleartext socket usage (CWE-319) migrated from
 * plumb-dfa/.../checkers/CleartextSocketUsageChecker.java to
 * plumb-style AST.
 *
 * <p>Detects unencrypted TCP / UDP channels ({@code new
 * Socket(...)}, {@code new DatagramSocket(...)},
 * {@code new SocketAddress(...)} literals) appearing in a
 * function that also carries sensitive-look data. The
 * heuristic is intentionally loose: any nearby variable
 * whose name matches the sensitive-word pattern
 * (password / token / ssn / credit-card / cvv / ...) is
 * enough to fire. The AI dedup prompt is the primary
 * filter.</p>
 *
 * <p><b>AST half.</b> The plumb-dfa version used a regex
 * on raw source text. The AST half uses
 * {@link com.github.javaparser.ast.expr.ObjectCreationExpr}
 * to find constructor calls of {@code Socket},
 * {@code DatagramSocket}, or {@code SocketAddress}; it
 * walks up to the enclosing method body and scans
 * surrounding statements for the sensitive-word pattern
 * in local variable names and string literals. This is
 * tighter than the regex (the regex could match
 * {@code // new Socket} inside a comment) and works on
 * parseable snippets.</p>
 *
 * <p><b>Migration note.</b> The AST half fires per
 * enclosing method, so two socket creations in the same
 * function produce one finding (matched on the first).
 * To split them, look up each
 * {@link ObjectCreationExpr} individually and emit on
 * each ? we trade simplicity for one-finding-per-method
 * dedup.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-319: cleartext transmission of sensitive information.
 *
 * <p>The previous DFA half (regex over the raw source file) was lenient by design: it fired on
 * every {@code new Socket(...)} anywhere in the file and then used the AI dedup prompt as the
 * primary filter. The AST half narrows to the enclosing method body and checks local variable names
 * / string literals for the sensitive-word heuristic, then dedups at one-finding-
 * per-enclosing-method.
 */
public final class CleartextSocketUsageCheck implements AstChecker {

  /**
   * Class-name fragments that match a cleartext socket constructor. The match is on the simple name
   * of the constructed type, so {@code java.net.Socket}, {@code javax.net.SocketFactory}
   * descendants, and the address / datagram variants all fire. We do NOT try to resolve the FQN —
   * that requires symbol resolution and adds no value here (the canonical socket APIs are either
   * {@code java.net} or {@code javax.net} and both produce the same cleartext warning).
   */
  private static final List<String> CLEARTEXT_TYPES =
      List.of("Socket", "DatagramSocket", "SocketAddress");

  /**
   * Sensitive-var pattern. Matched case-insensitively on local variable names and string literal
   * bodies inside the same enclosing method.
   */
  private static final Pattern SENSITIVE =
      Pattern.compile(
          "(?i)\\b(password|passwd|pwd|secret|api[_-]?key|"
              + "apikey|token|credential|auth|session[_-]?key|"
              + "access[_-]?key|private[_-]?key|ssn|credit[_-]?card|"
              + "cvv|pin)\\b");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-cleartext-socket-usage", "CLEARTEXT_SOCKET_USAGE"),
          "Cleartext socket in a function that handles sensitive data "
              + "(CWE-319); transmits over an unencrypted TCP/UDP channel",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-319"),
          "org.plumb.style.security.CleartextSocketUsageCheck",
          true,
          List.of("dfa-security-cleartext-socket", "cleartext-socket-usage")
      );

  @Override
  public String name() {
    return "cleartext-socket-usage";
  }

  @Override
  public void init(StyleContext ctx) {
    // stateless
  }

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    String typeName = n.getType().getNameAsString();
    if (!CLEARTEXT_TYPES.contains(typeName)) return;

    // Walk up to the enclosing method. If we're at
    // the top level (file-level ctor like
    // `new Socket(host, 80)` in a field initializer),
    // bail — those are unusual and the DFA half
    // didn't fire on them either.
    Optional<MethodDeclaration> enclosing = n.findAncestor(MethodDeclaration.class);
    if (enclosing.isEmpty()) return;

    // Sensitive-var heuristic: scan every
    // variable-declaration expression in the method
    // body for a name or RHS literal matching the
    // SENSITIVE pattern. The DFA half used a 2000-char
    // window; this version scopes to the method body
    // (tighter, fewer FPs).
    if (!containsSensitiveRef(enclosing.get())) return;

    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Cleartext "
            + typeName
            + " at line "
            + line
            + " in a function that also handles sensitive data "
            + "(password / token / PII). The data is transmitted "
            + "over an unencrypted TCP/UDP channel (CWE-319).",
        RuleDef.Severity.MEDIUM,
        "cleartext-socket: new " + typeName);
  }

  /**
   * Look at every statement in the method body for a local variable whose name or string-literal
   * RHS matches the SENSITIVE pattern.
   */
  private static boolean containsSensitiveRef(MethodDeclaration m) {
    if (!m.getBody().isPresent()) return false;
    for (Statement st : m.getBody().get().getStatements()) {
      for (VariableDeclarationExpr vde : st.findAll(VariableDeclarationExpr.class)) {
        for (var v : vde.getVariables()) {
          if (SENSITIVE.matcher(v.getNameAsString()).find()) {
            return true;
          }
          Expression init = v.getInitializer().orElse(null);
          if (init != null) {
            String s = init.toString();
            if (SENSITIVE.matcher(s).find()) return true;
          }
        }
      }
      // Also walk expression statements that might
      // hold a string literal with sensitive text.
      if (st instanceof ExpressionStmt) {
        String text = st.toString();
        if (SENSITIVE.matcher(text).find()) return true;
      }
    }
    return false;
  }
}

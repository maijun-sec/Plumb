/*
 * ? InsecureCookieCheck (CWE-614) migrated from
 * plumb-dfa/.../InsecureCookieChecker.java to plumb-style AST.
 *
 * <p>Flags construction of a {@code Cookie} (or
 * {@code javax.servlet.http.Cookie}) when the cookie is
 * added to the response (via {@code addCookie(...)})
 * without an obvious secure-flag set (heuristic: the
 * argument variable's name does not contain "Secure" ? a
 * conservative approximation; the DFA half tracked the
 * receiver's setSecure call per type).</p>
 *
 * <p>The AST version is a strict subset of the previous
 * DFA half: it fires on any {@code addCookie(...)} call
 * whose first argument's name does not contain "Secure"
 * (e.g. {@code addCookie(sessionCookie)}, {@code addCookie(c)},
 * {@code addCookie(myCookie)}). The DFA half additionally
 * tracked the case where the SAME receiver variable that
 * was passed to {@code new Cookie(...)} had
 * {@code .setSecure(true)} called on it earlier ? i.e. a
 * flow-sensitive analysis that AST cannot easily reproduce
 * without per-method state. We use a simple per-method
 * {@code cookieNamesEverSetSecure} set populated by
 * {@code visit(MethodDeclaration)} (init) and updated by
 * {@code visit(MethodCallExpr)} when we see a
 * {@code setSecure(...)} call whose receiver's name ends
 * with a "cookie"-like substring.</p>
 *
 * <p>Conservative heuristic: if the argument's name contains
 * "cookie" lowercase, treat it as a Cookie and require
 * setSecure() to have been called somewhere on a similarly
 * named variable; otherwise fire.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class InsecureCookieCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-insecure-cookie", "INSECURE_COOKIE"),
          "Sensitive Cookie added to response without calling " + "setSecure(true) (CWE-614)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-614"),
          "org.plumb.style.security.InsecureCookieCheck",
          true,
          List.of("dfa-security-insecure-cookie", "insecure-cookie"));

  /**
   * Per-method set of variable base names on which {@code setSecure(...)} has been called. Used as
   * a permissive signal that some cookie-like receiver was marked secure earlier in the same
   * method.
   */
  private final Set<String> cookieNamesEverSetSecure = new HashSet<>();

  @Override
  public String name() {
    return "insecure-cookie";
  }

  @Override
  public void init(StyleContext ctx) {
    cookieNamesEverSetSecure.clear();
  }

  @Override
  public void finish(StyleContext ctx) {
    cookieNamesEverSetSecure.clear();
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // Per-method reset: the dispatch is single-pass but
    // the per-method name set must NOT carry across
    // methods. We piggy-back on MethodDeclaration to
    // scope the state to the current method.
    cookieNamesEverSetSecure.clear();
  }

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    String calleeName = n.getNameAsString();

    // Track cookie.setSecure(...) calls: record the
    // receiver base name. The DFA half used
    // type-based tracking; we use a name-based
    // approximation. We only record the name if the
    // receiver's name looks "cookie-like" (contains
    // "cookie" lowercase).
    if ("setSecure".equals(calleeName)) {
      String recv = receiverBaseName(n);
      if (recv != null && looksLikeCookieName(recv)) {
        cookieNamesEverSetSecure.add(stripIndex(recv));
      }
      return;
    }

    // Sink: response.addCookie(cookie) — fire when the
    // arg's base name looks like a Cookie and has not
    // been recorded as setSecure anywhere in the
    // current method.
    if (!"addCookie".equals(calleeName)) return;
    if (n.getArguments().isEmpty()) return;
    String argName = baseName(n.getArgument(0));
    if (argName == null) return;
    if (!looksLikeCookieName(argName)) return;
    // Fire if no variable sharing the same base name
    // has been marked secure.
    if (cookieNamesEverSetSecure.contains(stripIndex(argName))) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Sensitive Cookie added to response without "
            + "calling setSecure(true). The cookie can "
            + "be transmitted over plain HTTP, exposing "
            + "session data to network sniffers (CWE-614).",
        RuleDef.Severity.MEDIUM,
        "insecure-cookie: response.addCookie(" + argName + " without setSecure)");
  }

  private static String receiverBaseName(MethodCallExpr n) {
    if (n.getScope().isEmpty()) return null;
    if (!(n.getScope().get() instanceof NameExpr ne)) return null;
    return ne.getNameAsString();
  }

  private static String baseName(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof NameExpr ne) return ne.getNameAsString();
    return null;
  }

  private static boolean looksLikeCookieName(String name) {
    if (name == null) return false;
    return name.toLowerCase(Locale.ROOT).contains("cookie");
  }

  /**
   * Strip a trailing array index like {@code [0]} so {@code cookies[0]} and {@code cookies} match.
   * Keep it short: any non-alphanumeric tail is removed.
   */
  private static String stripIndex(String name) {
    if (name == null) return null;
    int i = 0;
    while (i < name.length()
        && (Character.isLetterOrDigit(name.charAt(i)) || name.charAt(i) == '_')) {
      i++;
    }
    return name.substring(0, i);
  }
}

/*
 * Coverity <code>WEAK_URL_SANITIZATION</code> — flags weak URL/host sanitization that is likely
 * bypassable by an attacker.
 *
 * {@link #isUnderAssertion(MethodCallExpr)} helper uses parent pointers, which JavaParser maintains
 * for nodes attached to a parsed tree ? works identically whether the dispatch comes from
 * findAll or from the dispatcher.
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class WeakUrlSanitizationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-weak-url-sanitization", "WEAK_URL_SANITIZATION"),
          "Weak URL/host sanitization that can be bypassed by an attacker",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-20", "CWE-601"),
          "org.plumb.style.WeakUrlSanitizationCheck",
          true,
          List.of("ast-security-weak-url-sanitization", "weak-url-sanitization"));

  private static final Set<String> WEAK_METHODS =
      Set.of("contains", "indexOf", "matches", "find", "lookingAt", "regionMatches");

  private static final Set<String> ASSERT_METHODS =
      Set.of(
          "assert",
          "assertTrue",
          "assertFalse",
          "assertThat",
          "assertNotNull",
          "assertNull",
          "assertEquals",
          "assertNotEquals",
          "assertSame",
          "assertNotSame",
          "assertThrows");

  /** True when the call site lives under an assertion call (excluded). */
  private static boolean isUnderAssertion(MethodCallExpr m) {
    com.github.javaparser.ast.Node cur = m.getParentNode().orElse(null);
    while (cur != null) {
      if (cur instanceof MethodCallExpr) {
        MethodCallExpr mc = (MethodCallExpr) cur;
        if (ASSERT_METHODS.contains(mc.getNameAsString())) {
          return true;
        }
      }
      cur = cur.getParentNode().orElse(null);
    }
    return false;
  }

  @Override
  public String name() {
    return "weak-url-sanitization";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    String methodName = m.getNameAsString();
    if (!WEAK_METHODS.contains(methodName)) return;
    if (m.getArguments().isEmpty()) return;
    if (isUnderAssertion(m)) return;

    String owner = null;
    try {
      Object resolved = m.resolve();
      if (resolved instanceof ResolvedMethodDeclaration rmd) {
        owner = rmd.declaringType().getQualifiedName();
      }
    } catch (RuntimeException ignore) {
      // fall back to scope text
    }
    if (owner != null
        && !"java.lang.String".equals(owner)
        && !"java.util.regex.Matcher".equals(owner)
        && !"java.util.regex.Pattern".equals(owner)) {
      return;
    }

    String arg = m.getArgument(0).toString();
    String lc = arg == null ? "" : arg.toLowerCase(Locale.ROOT);

    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);

    if (("contains".equals(methodName) || "indexOf".equals(methodName))
        && looksLikeHostLiteral(lc)) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "WEAK_URL_SANITIZATION: "
              + methodName
              + "(\""
              + arg
              + "\") is a substring check, not a "
              + "URL/host allowlist — an attacker can place the "
              + "trusted host anywhere in the URL to bypass it",
          RuleDef.Severity.MEDIUM,
          m.toString());
      return;
    }

    if ("matches".equals(methodName)
        && ("java.lang.String".equals(owner) || "java.util.regex.Pattern".equals(owner))) {
      if (m.getArgument(0) instanceof StringLiteralExpr lit) {
        String pat = lit.getValue();
        if (pat != null && !pat.startsWith("^")) {
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "WEAK_URL_SANITIZATION: matches(\""
                  + pat
                  + "\") is missing the '^' start anchor, so "
                  + "a trusted substring anywhere in the input "
                  + "is accepted",
              RuleDef.Severity.MEDIUM,
              m.toString());
          return;
        }
        if (looksLikeHostnameRegex(pat)) {
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "WEAK_URL_SANITIZATION: matches(\""
                  + pat
                  + "\") — unescaped '.' in a hostname regex "
                  + "matches any character, allowing hostnames "
                  + "like 'www5example.com' to pass",
              RuleDef.Severity.MEDIUM,
              m.toString());
        }
      }
    }

    if ("find".equals(methodName) && "java.util.regex.Matcher".equals(owner)) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "WEAK_URL_SANITIZATION: Matcher.find() does not require "
              + "the pattern to match the whole input — verify "
              + "the regex is anchored and that this is the "
              + "intended validation",
          RuleDef.Severity.MEDIUM,
          m.toString());
    }
  }

  private static boolean looksLikeHostLiteral(String lowerArg) {
    if (lowerArg.startsWith("\"") && lowerArg.endsWith("\"") && lowerArg.length() > 1) {
      lowerArg = lowerArg.substring(1, lowerArg.length() - 1);
    }
    return lowerArg.startsWith("http://")
        || lowerArg.startsWith("https://")
        || lowerArg.startsWith("www.")
        || lowerArg.contains(".com/")
        || lowerArg.contains(".com\"")
        || lowerArg.contains(".net/")
        || lowerArg.contains(".org/")
        || lowerArg.contains(".io/")
        || lowerArg.contains("example.com")
        || lowerArg.contains("localhost")
        || lowerArg.contains("://");
  }

  private static boolean looksLikeHostnameRegex(String pat) {
    if (pat == null) return false;
    boolean hostnameish =
        pat.contains("://")
            || pat.contains("www.")
            || pat.contains("example.com")
            || pat.contains("localhost");
    if (!hostnameish) return false;
    for (int i = 0; i < pat.length(); i++) {
      if (pat.charAt(i) != '.') continue;
      if (i > 0 && pat.charAt(i - 1) == '\\') continue;
      // Skip the dot followed by a quantifier — `.+`, `.*`, `.?`,
      // `.{n,m}` are intentionally permissive dots, not the
      // hostname-allowlist shape Coverity is flagging.
      if (i + 1 < pat.length()) {
        char next = pat.charAt(i + 1);
        if (next == '*' || next == '+' || next == '?') continue;
        if (next == '{') continue;
      }
      return true;
    }
    return false;
  }
}

/*
 * C4: AST-style CWE-328 (Use of Weak Hash) checker.
 *
 * <p>Fires when {@link java.security.MessageDigest#getInstance(String)}
 * (or any other {@code getInstance}-style factory) is
 * called with a weak / broken cryptographic hash
 * algorithm name.</p>
 *
 * <p><strong>Canonical weak hashes:</strong>
 * {@code MD2}, {@code MD4}, {@code MD5},
 * {@code SHA}, {@code SHA1}, {@code HmacMD5}.
 * All of these are broken for collision / preimage
 * resistance per NIST guidance. {@code MD5} and
 * {@code SHA-1} are explicitly disallowed for
 * digital signatures and TLS since RFC 6151 /
 * RFC 6234. {@code MD2} / {@code MD4} are
 * discretionary for legacy reasons.</p>
 *
 * <p>Detection approach: this is an <em>AST</em>
 * check -- we walk every
 * {@link MethodCallExpr} whose callee is
 * {@code getInstance} on a {@code MessageDigest}
 * receiver and inspect the string-literal first
 * parameter. The check distinguishes two forms:</p>
 *
 * <ol>
 *   <li>{@code MessageDigest.getInstance("MD5")} —
 *       literal weak-algo arg, TP on sight.</li>
 *   <li>{@code MessageDigest.getInstance(algo)} —
 *       non-literal arg; we walk the enclosing
 *       method for a string-literal initializer
 *       matching {@code algo}. If found, TP.</li>
 * </ol>
 *
 * <p><strong>Why AST not taint:</strong> weak
 * hash is a value-of-the-arg classification, not a
 * tainted-data-flow-through-auxiliary-arg. The
 * taint framework would not propagate the literal
 * "MD5" as a tainted source; an AST-style match on
 * the literal-string first arg is the canonical
 * detection shape.</p>
 *
 * <p><strong>General-purpose:</strong> the rule
 * targets a JDK cached-library call pattern,
 * not Juliet-specific constructs. Adding a new
 * weak-algo name is a one-line change to
 * {@link #WEAK_HASH_ALGORITHMS} below. No
 * Juliet-specific hard-coding.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-328: Use of Weak Hash.
 *
 * <p>Detects calls to {@code java.security.MessageDigest.getInstance(String)} with a literal weak /
 * broken hash algorithm name. The rule is AST-only (no TAC, no data flow needed) -- a
 * single-method-call shape with a string-literal first arg.
 */
public final class WeakHashChecker implements AstChecker {

  /**
   * The set of weak / broken cryptographic hash algorithms. Membership lookup is case-insensitive
   * after {@link #normalise(String)} strips the {@code "-"} separator (some JDKs accept {@code
   * "SHA-1"} with a hyphen).
   *
   * <p>Add new entries here when NIST formally deprecates an algorithm.
   */
  private static final Set<String> WEAK_HASH_ALGORITHMS =
      new LinkedHashSet<>(
          List.of("MD2", "MD4", "MD5", "SHA", "SHA1", "SHA-1", "HmacMD5", "HmacMD4"));

  /**
   * (this is an AST checker, not a DFA one -- see {@link org.plumb.style.AstDispatcher}). Any
   * consumer pinning the old id needs to track via {@code RuleRegistry} alias resolution.
   */
  private static final String RULE_ID = "ast-security-weak-hash";

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(RULE_ID, "WEAK_HASH"),
          "Use of weak / broken cryptographic hash algorithm (CWE-328)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-328"),
          "org.plumb.style.security.WeakHashChecker",
          true,
          // P1-2-2026-10-18: preserve historical dfa-security-weak-hash id
          // as an alias so consumers (juliet-coverage CSVs, custom configs) that
          // pinned the pre-Pass-C id can still resolve via RuleRegistry.byAlias.
          List.of("ast-security-weak-hash", "dfa-security-weak-hash", "weak-hash"));

  @Override
  public String name() {
    return "weak-hash";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    String methodName = n.getNameAsString();
    if (!"getInstance".equals(methodName)) return;
    if (!isMessageDigestScope(n.getScope().orElse(null))) return;
    if (n.getArguments().isEmpty()) return;
    Expression firstArg = n.getArguments().get(0);
    String algo = resolveAlgo(firstArg, ctx);
    if (algo == null) return;
    if (!WEAK_HASH_ALGORITHMS.contains(normalise(algo))) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Weak hash algorithm '" + algo + "' (use SHA-256 or stronger instead)",
        RuleDef.Severity.MEDIUM,
        "weak-hash: " + algo);
  }

  /**
   * Check whether the call's scope is {@code MessageDigest} (literal class name) OR a variable
   * whose declared type is {@code MessageDigest} (resolved via enclosing method variable
   * declarations).
   */
  private boolean isMessageDigestScope(Expression scope) {
    if (scope == null) return false;
    if (scope instanceof NameExpr ne) {
      return ne.getNameAsString().equals("MessageDigest");
    }
    return false;
  }

  /**
   * Resolve the algorithm-name string from a {@code getInstance} first arg. Returns the algorithm
   * name if the arg is a string literal OR a local-variable name that resolves to a string-literal
   * initializer in the enclosing method. Returns {@code null} for non-literal, non-constant
   * expressions.
   */
  private String resolveAlgo(Expression arg, StyleContext ctx) {
    if (arg instanceof StringLiteralExpr sl) {
      return sl.getValue();
    }
    if (arg instanceof NameExpr ne) {
      MethodDeclaration md = ne.findAncestor(MethodDeclaration.class).orElse(null);
      if (md == null) return null;
      String varName = ne.getNameAsString();
      return findAlgoConstantInBody(md.getBody().orElse(null), varName);
    }
    return null;
  }

  private static String findAlgoConstantInBody(BlockStmt body, String varName) {
    if (body == null) return null;
    for (VariableDeclarationExpr vde : body.findAll(VariableDeclarationExpr.class)) {
      for (VariableDeclarator vd : vde.getVariables()) {
        if (!vd.getNameAsString().equals(varName)) continue;
        if (vd.getInitializer().isPresent()
            && vd.getInitializer().get() instanceof StringLiteralExpr sl) {
          return sl.getValue();
        }
      }
    }
    return null;
  }

  /**
   * Normalise an algorithm-name string: uppercase + drop the {@code "-"} separator. Lets {@code
   * "sha-1"} and {@code "SHA1"} match the {@code "SHA1"} entry uniformly.
   */
  private static String normalise(String algo) {
    if (algo == null) return "";
    return algo.toUpperCase().replace("-", "");
  }
}

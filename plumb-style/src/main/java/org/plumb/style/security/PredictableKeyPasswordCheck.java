/*
 * Flags a key or password that is predictable rather than randomly
 * generated (CWE-259 / CWE-798).
 *
 * <p>Flags a hardcoded password/seed/salt passed to
 * {@code PBEKeySpec}, {@code PBEParameterSpec},
 * {@code SecretKeySpec}, or the secret-key family.
 * The DFA half also walked a single MOVE to recover
 * {@code String pw = "..."; new PBEKeySpec(pw.toCharArray());}
 * but the AST half only fires on literal arguments
 * (i.e. the call site inlines the secret).</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class PredictableKeyPasswordCheck implements AstChecker {

  /** Class types whose password / salt / secret-key argument must not be a compile-time literal. */
  private static final Set<String> FORBIDDEN_TYPES =
      Set.of(
          "PBEKeySpec",
          "PBEParameterSpec",
          "SecretKeySpec",
          "KeySpec",
          "DHPrivateKeySpec",
          "DHParameterSpec");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-predictable-key-password", "PREDICTABLE_KEY_PASSWORD"),
          "Hardcoded password / salt / key literal (CWE-259 / CWE-798)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.SECURITY,
          List.of("CWE-259", "CWE-798"),
          "org.plumb.style.security.PredictableKeyPasswordCheck",
          true,
          List.of("dfa-security-hardcoded-password", "predictable-key-password"));

  @Override
  public String name() {
    return "predictable-key-password";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    String typeName = n.getType().getNameAsString();
    if (!FORBIDDEN_TYPES.contains(typeName)) return;
    if (n.getArguments().isEmpty()) return;
    // First arg is conventionally the password / salt /
    // secret-key. Look for any literal there.
    com.github.javaparser.ast.expr.Expression arg = n.getArgument(0);
    if (isHardcodedLiteral(arg)) {
      ctx.report(
          n.getBegin().map(p -> p.line).orElse(-1),
          n.getBegin().map(p -> p.column).orElse(-1),
          DEFAULT_RULE,
          "Hardcoded "
              + lower(typeName)
              + " literal in "
              + "new "
              + typeName
              + "(...) — use a runtime "
              + "value, not a compile-time constant (CWE-259)",
          RuleDef.Severity.HIGH,
          n.toString());
    }
  }

  private static boolean isHardcodedLiteral(com.github.javaparser.ast.expr.Expression e) {
    if (e == null) return false;
    return e instanceof StringLiteralExpr
        || e instanceof CharLiteralExpr
        || e instanceof IntegerLiteralExpr;
  }

  private static String lower(String s) {
    return s.toLowerCase(java.util.Locale.ROOT);
  }
}

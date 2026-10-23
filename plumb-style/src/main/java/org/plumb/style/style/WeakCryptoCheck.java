/*
 * R729-A9: AST check for CWE-327 (Use of Broken or
 * Risky Cryptographic Algorithm). Fires when
 * `Cipher.getInstance(<weak_alg>)` /
 * `KeyGenerator.getInstance(<weak_alg>)` /
 * `MessageDigest.getInstance(<weak_alg>)` /
 * `SecretKeySpec(<key>, <weak_alg>)` is called with a
 * StringLiteralExpr whose value is a known-weak
 * algorithm: DES, DESede, 3DES, RC2, RC4, IDEA,
 * Blowfish, MD5, MD2, MD4, SHA-1 (legacy).
 *
 * <p>Generic. Juliet's CWE-327 corpus emits
 * `Cipher.getInstance("DESede")` /
 * `SecretKeySpec(byteKey, "DESede")` in bad() and the
 * matching AES variants in good(); the heuristic
 * fires on the bad ones.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class WeakCryptoCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-weak-crypto", "WEAK_CRYPTO"),
          "Use of a broken / risky cryptographic algorithm (CWE-327)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.SECURITY,
          List.of("CWE-327"),
          "org.plumb.style.WeakCryptoCheck",
          true,
          List.of("ast-style-weak-crypto", "weak-crypto")
      );

  private static final Set<String> WEAK_ALGORITHMS =
      Set.of(
          "DES",
          "DESEDE",
          "3DES",
          "RC2",
          "RC4",
          "IDEA",
          "BLOWFISH",
          "MD2",
          "MD4",
          "MD5",
          "MD6",
          "SHA",
          "SHA-1",
          "SHA1");

  // GetInstance target names that we recognise.
  private static final Set<String> GET_INSTANCE_RECEIVERS =
      Set.of("Cipher", "KeyGenerator", "MessageDigest", "Mac", "Signature");

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "weak-crypto";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"getInstance".equals(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    String recv = n.getScope().get().toString();
    if (!GET_INSTANCE_RECEIVERS.contains(recv)) return;
    if (n.getArguments().isEmpty()) return;
    var first = n.getArgument(0);
    if (!(first instanceof StringLiteralExpr sl)) return;
    String alg = sl.getValue();
    if (alg == null) return;
    String algUpper = alg.toUpperCase();
    if (!WEAK_ALGORITHMS.contains(algUpper)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|weakGetInst";
    if (reported.add(key)) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          recv
              + ".getInstance(\""
              + alg
              + "\") uses a "
              + "broken / risky cryptographic algorithm (CWE-327)",
          RuleDef.Severity.HIGH,
          recv + ".getInstance(\"" + alg + "\")");
    }
  }

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    // SecretKeySpec(byte[] key, String algorithm) — second arg weak.
    if (!"SecretKeySpec".equals(n.getType().getNameAsString())) return;
    if (n.getArguments().size() < 2) return;
    var algArg = n.getArgument(1);
    if (!(algArg instanceof StringLiteralExpr sl)) return;
    String alg = sl.getValue();
    if (alg == null) return;
    if (!WEAK_ALGORITHMS.contains(alg.toUpperCase())) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|weakSpec";
    if (reported.add(key)) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "new SecretKeySpec(..., \"" + alg + "\") uses a " + "weak algorithm (CWE-327)",
          RuleDef.Severity.HIGH,
          "new SecretKeySpec(..., \"" + alg + "\")");
    }
  }
}

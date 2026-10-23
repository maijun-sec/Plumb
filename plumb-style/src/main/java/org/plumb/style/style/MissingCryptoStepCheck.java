package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-325 (Missing Required Cryptographic Step).
 *
 * <p>Detects code that uses {@code Cipher} (encryption) without a corresponding {@code Mac}
 * (message authentication) step. The encryption-only scheme omits the message authentication that
 * CWE-325 requires.
 *
 * <p>The previous implementation was a file-wide regex scan ({@code
 * ClassThreadChecker.MissingCryptoStepChecker}). R787f replaces it with an AST walk of every {@link
 * MethodCallExpr}: any call to {@code Cipher.getInstance / init / doFinal / update} without any
 * sibling {@code Mac.*} call in the same file fires the finding on the first cipher call line.
 */
public final class MissingCryptoStepCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          // R787f: rule_id aligned with
          // plumb_rule_to_cwe.json (`Cwe325` —
          // ["325"]).
          new RuleId("ast-style-missing-crypto-step", "MISSING_CRYPTO_STEP"),
          "Cipher used without MAC step (CWE-325).",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-325"),
          "org.plumb.style.MissingCryptoStepCheck",
          true,
          List.of("ast-style-missing-crypto-step-style", "missing-crypto-step")
      );

  /** Per-file accumulator state. Cleared in init() before each file's walk. */
  private final Set<Integer> cipherLines = new HashSet<>();
  private boolean hasMac = false;
  private boolean reported = false;

  @Override
  public String name() {
    return "missing-crypto-step";
  }

  @Override
  public void init(StyleContext ctx) {
    cipherLines.clear();
    hasMac = false;
    reported = false;
  }

  @Override
  public void visit(MethodCallExpr mc, StyleContext ctx) {
    String name = mc.getNameAsString();
    if (!isCipherOrMacCall(name)) return;
    String recv = receiverName(mc.getScope().orElse(null));
    if (recv == null) return;
    int line = mc.getBegin().map(p -> p.line).orElse(-1);
    if ("Cipher".equals(recv)) {
      cipherLines.add(line);
    } else if ("Mac".equals(recv)) {
      hasMac = true;
    }
  }

  @Override
  public void finish(StyleContext ctx) {
    if (reported) return;
    if (cipherLines.isEmpty()) return;
    if (hasMac) return;
    int firstLine = cipherLines.stream().mapToInt(Integer::intValue).min().orElse(-1);
    ctx.report(
        firstLine,
        -1,
        DEFAULT_RULE,
        "Cipher used without MAC step (CWE-325).",
        RuleDef.Severity.MEDIUM,
        "missing-crypto-step@line " + firstLine);
    reported = true;
  }

  private static boolean isCipherOrMacCall(String name) {
    switch (name) {
      case "getInstance":
      case "init":
      case "doFinal":
      case "update":
        return true;
      default:
        return false;
    }
  }

  /**
   * Returns the simple name of the receiver if it is a {@link NameExpr}; otherwise null. The caller
   * compares to "Cipher" / "Mac" to classify the call.
   */
  private static String receiverName(com.github.javaparser.ast.expr.Expression scope) {
    if (scope instanceof NameExpr ne) {
      return ne.getNameAsString();
    }
    return null;
  }
}

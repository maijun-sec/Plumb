/*
 * C5: AST check for CWE-549 (Missing Password
 * Masking). Fires when a string literal containing
 * `name="password"` (with optional preceding whitespace)
 * is followed in the same statement by `type="text"`.
 *
 * <p>Pattern is: input element with a `password` name
 * attribute whose `type` attribute is `text` instead of
 * `password`.</p>
 *
 * <p>Generic — the heuristic is "password literal
 * field rendered without type=password mask". Does
 * not depend on any specific file layout.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class MissingPasswordMaskingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-missing-password-masking", "MISSING_PASSWORD_MASKING"),
          "Password field rendered as type='text' — use type='password' (CWE-549)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-549"),
          "org.plumb.style.MissingPasswordMaskingCheck",
          true,
          List.of("ast-style-missing-password-masking", "missing-password-masking"));

  // JavaParser's StringLiteralExpr.getValue() returns the
  // raw text including the `\"` escapes — the raw
  // sequence for `<input name=\"password\" ...>` is
  // backslash quote + password + backslash + quote.
  // We probe for the password-name attribute and a
  // bad-type attribute in the SAME string literal.
  private static final String NEEDLE_PASSWORD = "name=\\\"password\\\"";
  private static final String NEEDLE_TYPE_TEXT = "type=\\\"text\\\"";
  private static final String NEEDLE_TYPE_PW = "type=\\\"password\\\"";

  private static boolean isMissingPasswordMask(String v) {
    // Both attributes must be present in the SAME
    // string literal — that's how Juliet's CWE-549
    // emits the bad pattern. The needle strings
    // already encode the `\"` JavaParser returns.
    int pwIdx = v.indexOf(NEEDLE_PASSWORD);
    if (pwIdx < 0) return false;
    int badIdx = v.indexOf(NEEDLE_TYPE_TEXT, pwIdx);
    if (badIdx < 0) return false;
    // If the SAME string also has type="password"
    // the developer masked some password fields but
    // not this one — still a violation since the
    // password literal is unmasked.
    return true;
  }

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "missing-password-masking";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(StringLiteralExpr n, StyleContext ctx) {
    String v = n.getValue();
    if (v == null || v.length() < 16) return;
    if (!isMissingPasswordMask(v)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|pwTypeText";
    if (reported.add(key)) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Password input rendered as type=\"text\" — " + "use type=\"password\" (CWE-549)",
          RuleDef.Severity.MEDIUM,
          v);
    }
  }
}

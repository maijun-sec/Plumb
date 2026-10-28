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
 * Batch 3 -- literal-sourced string assignment
 * (CWE-798 hint).
 *
 * <p>Sketch migration of the {@code LiteralSourcedTracker}
 * pattern: a String literal of at least 8 characters is
 * assigned to a local variable (i.e. used as a
 * "literal-sourced" value). This is a high-entropy / hard-
 * coded-credential indicator and should be reviewed.</p>
 *
 * <p>Like {@code HighEntropyStringCheck}, this is a
 * sketch: the AST half fires on every
 * {@code String password = "..."} / similar assignment
 * &ge; 8 chars. The AI dedup prompt is the primary
 * filter. The legacy DFA file at
 * {@code plumb-dfa/.../checkers/LiteralSourcedTracker.java}
 * was a utility class used by HardCodedPasswordSink and
 * HardCodedKey checkers; it is NOT moved to backup.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import java.util.List;
import java.util.Locale;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-798: hard-coded credential hint (literal-sourced string).
 *
 * <p>Fires on every local-variable assignment of a String literal &ge; 8 chars. The check is
 * sketch-level: the AI dedup prompt is the primary filter; FP rate is intentionally high so we
 * don't miss real secrets.
 */
public final class LiteralSourcedHintCheck implements AstChecker {

  private static final int MIN_LENGTH = 8;

  /**
   * Variable names that suggest the literal is a credential. Names matching this pattern get the
   * higher-priority CWE-798; other long-string assignments get a softer INFO. The DFA half used
   * length &gt; 8 in the tracker without a name filter -- we add the name filter for fewer FPs.
   */
  private static final java.util.regex.Pattern SENSITIVE_NAME =
      java.util.regex.Pattern.compile(
          "(?i).*(password|passwd|pwd|secret|api[_-]?key|"
              + "apikey|token|credential|auth|salt|"
              + "private[_-]?key).*");

  private static final List<String> PLACEHOLDER_TOKENS =
      List.of(
          "<your-key>",
          "<your-secret>",
          "<your-password>",
          "<password>",
          "<secret>",
          "<token>",
          "changeme",
          "your-api-key");

  private static final RuleDef SENSITIVE_RULE =
      new RuleDef(
          new RuleId("ast-security-literal-sourced-hint", "LITERAL_SOURCED_HINT"),
          "Local variable with a sensitive name is assigned "
              + "a String literal of "
              + MIN_LENGTH
              + "+ chars "
              + "(likely hard-coded credential) (CWE-798)",
          RuleDef.Severity.LOW,
          RuleDef.Type.SECURITY,
          List.of("CWE-798"),
          "org.plumb.style.security.LiteralSourcedHintCheck",
          true,
          List.of("ast-security-literal-sourced-hint", "literal-sourced-hint"));

  // Canonical DEFAULT_RULE — the registry mirror uses this to get
  // a non-synthetic RuleDef with full CWE list. The visit() method
  // picks SENSITIVE_RULE or GENERIC_RULE based on the variable name.
  private static final RuleDef DEFAULT_RULE = SENSITIVE_RULE;

  private static final RuleDef GENERIC_RULE =
      new RuleDef(
          new RuleId("dfa-security-literal-sourced-generic", "LITERAL_SOURCED_GENERIC"),
          "Local variable is assigned a String literal of "
              + MIN_LENGTH
              + "+ chars; "
              + "review for hard-coded credential (CWE-798)",
          RuleDef.Severity.INFO,
          RuleDef.Type.SECURITY,
          List.of("CWE-798"),
          "org.plumb.style.security.LiteralSourcedHintCheck",
          true,
          List.of("dfa-security-literal-sourced-sensitive", "literal-sourced-hint"));

  @Override
  public String name() {
    return "literal-sourced-hint";
  }

  @Override
  public void init(StyleContext ctx) {
    // stateless
  }

  @Override
  public void visit(VariableDeclarationExpr n, StyleContext ctx) {
    for (VariableDeclarator v : n.getVariables()) {
      String name = v.getNameAsString();
      Expression init = v.getInitializer().orElse(null);
      if (!(init instanceof StringLiteralExpr sl)) continue;

      String value = sl.getValue();
      if (value == null || value.length() < MIN_LENGTH) continue;

      String low = value.toLowerCase(Locale.ROOT);
      for (String ph : PLACEHOLDER_TOKENS) {
        if (low.contains(ph)) return;
      }

      boolean sensitive = SENSITIVE_NAME.matcher(name).matches();
      RuleDef rule = sensitive ? SENSITIVE_RULE : GENERIC_RULE;
      RuleDef.Severity sev = sensitive ? RuleDef.Severity.LOW : RuleDef.Severity.INFO;

      int line = v.getBegin().map(p -> p.line).orElse(n.getBegin().map(p -> p.line).orElse(-1));
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      String display = value.length() > 24 ? value.substring(0, 24) + "..." : value;

      ctx.report(
          line,
          col,
          rule,
          (sensitive
              ? "Local '"
                  + name
                  + "' assigned a literal "
                  + "of "
                  + value.length()
                  + " chars — "
                  + "likely hard-coded credential (CWE-798)"
              : "Local '"
                  + name
                  + "' assigned a literal "
                  + "of "
                  + value.length()
                  + " chars; "
                  + "review for hard-coded credential "
                  + "(CWE-798)"),
          sev,
          "literal-sourced-hint: " + name + " = \"" + display + "\"");
    }
  }
}

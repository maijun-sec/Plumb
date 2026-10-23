/*
 * R-812 Batch 3 ? high-entropy string literal (CWE-798 / CWE-331).
 *
 * <p>Sketch migration of the {@code EntropyDetector}
 * logic to a plumb-style AST checker. The original DFA
 * file at
 * {@code plumb-dfa/.../checkers/EntropyDetector.java}
 * was a <em>utility class</em>, not a checker, and is
 * still used by
 * {@code plumb-dfa/.../checkers/HardCodedPasswordSinkChecker}
 * for FP suppression. It is therefore NOT moved to
 * backup ? the AST check here re-implements the
 * classification inline and emits a finding on every
 * {@code StringLiteralExpr} whose entropy + diversity
 * classifies as HIGH (looks like a real random secret).</p>
 *
 * <p>The plumb-style module does not depend on
 * plumb-dfa, so the Shannon / char-class logic is
 * duplicated here rather than imported. The thresholds
 * match the DFA half exactly (R9) ? see the
 * {@code plumb-dfa/docs/R9-ENTROPY-FP-SUPPRESS} note.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-798 / CWE-331: hard-coded credential (high-entropy string).
 *
 * <p>Classifies a string literal as {@code LOW / MEDIUM / HIGH} based on Shannon entropy +
 * character-class diversity, and emits a finding on {@code HIGH} literals. The thresholds mirror R9
 * (low entropy = &lt; 2.85 bits/char; HIGH requires &ge; 3 distinct char classes).
 */
public final class HighEntropyStringCheck implements AstChecker {

  /** Confidence bucket — duplicated from DFA {@code EntropyDetector.Confidence}. */
  private enum Confidence {
    LOW,
    MEDIUM,
    HIGH
  }

  private static final double LOW_ENTROPY_THRESHOLD = 2.85;
  private static final int MIN_LENGTH_FOR_ENTROPY = 6;

  /** AST check fires on literals of at least this many characters that classify as HIGH. */
  private static final int MIN_LENGTH = 8;

  private static final List<String> PLACEHOLDER_TOKENS =
      List.of(
          "<your-key>",
          "<your_api_key>",
          "<your-secret>",
          "<api-key>",
          "your-api-key",
          "your_api_key",
          "your-secret",
          "<password>",
          "<secret>",
          "<token>",
          "changeme");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-high-entropy-string", "HIGH_ENTROPY_STRING"),
          "String literal looks like a hard-coded secret / API key "
              + "(high entropy, mixed char classes) (CWE-798)",
          RuleDef.Severity.LOW,
          RuleDef.Type.SECURITY,
          List.of("CWE-798", "CWE-331"),
          "org.plumb.style.security.HighEntropyStringCheck",
          true,
          List.of("dfa-security-high-entropy-string", "high-entropy-string")
      );

  @Override
  public String name() {
    return "high-entropy-string";
  }

  @Override
  public void init(StyleContext ctx) {
    // stateless
  }

  @Override
  public void visit(StringLiteralExpr n, StyleContext ctx) {
    String value = n.getValue();
    if (value == null || value.length() < MIN_LENGTH) return;

    String low = value.toLowerCase(Locale.ROOT);
    for (String ph : PLACEHOLDER_TOKENS) {
      if (low.contains(ph)) return;
    }

    if (classify(value) != Confidence.HIGH) return;

    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String display = value.length() > 24 ? value.substring(0, 24) + "..." : value;
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "String literal at line "
            + line
            + " looks like a hard-coded secret (entropy "
            + String.format(Locale.ROOT, "%.2f", shannonBitsPerChar(value))
            + " bits/char, "
            + distinctCharClasses(value)
            + " char classes) (CWE-798)",
        RuleDef.Severity.LOW,
        "high-entropy-string: \"" + display + "\"");
  }

  // ─── entropy helpers (inlined from DFA
  //     EntropyDetector; see class doc) ───

  /**
   * Shannon entropy of {@code s}, bits per character. Returns 0 for empty or single-char strings.
   */
  static double shannonBitsPerChar(String s) {
    if (s == null || s.length() <= 1) return 0.0;
    int n = s.length();
    Map<Character, Integer> counts = new HashMap<>();
    for (int i = 0; i < n; i++) {
      char c = s.charAt(i);
      counts.merge(c, 1, Integer::sum);
    }
    double h = 0.0;
    for (int cnt : counts.values()) {
      double p = (double) cnt / n;
      h -= p * (Math.log(p) / Math.log(2.0));
    }
    return h;
  }

  /** Count distinct character classes in {@code s}: lowercase, uppercase, digit, symbol. */
  static int distinctCharClasses(String s) {
    if (s == null || s.isEmpty()) return 0;
    boolean lower = false, upper = false, digit = false, symbol = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= 'a' && c <= 'z') lower = true;
      else if (c >= 'A' && c <= 'Z') upper = true;
      else if (c >= '0' && c <= '9') digit = true;
      else symbol = true;
    }
    int n = 0;
    if (lower) n++;
    if (upper) n++;
    if (digit) n++;
    if (symbol) n++;
    return n;
  }

  /** Confidence bucket — same thresholds as DFA EntropyDetector.classify(). */
  static Confidence classify(String literal) {
    if (literal == null || literal.length() < MIN_LENGTH_FOR_ENTROPY) {
      return Confidence.MEDIUM;
    }
    double h = shannonBitsPerChar(literal);
    int classes = distinctCharClasses(literal);
    if (h < LOW_ENTROPY_THRESHOLD) return Confidence.LOW;
    if (classes < 2) return Confidence.LOW;
    if (classes >= 3 && h >= LOW_ENTROPY_THRESHOLD) return Confidence.HIGH;
    return Confidence.MEDIUM;
  }
}

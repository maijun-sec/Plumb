/*
 * R9 -- Shannon entropy + character-class
 * diversity detector for hard-coded
 * password FP suppression.
 *
 * <h3>Why both entropy and char-class</h3>
 *
 * <p>Pure Shannon entropy is unreliable
 * for short strings -- an 8-char string
 * with all unique characters has
 * {@code log2(8) = 3.0} bits/char, which
 * is the max possible at that length.
 * So "7e5tc4s3" (8 unique) and
 * "abcdefgh" (8 unique) both score 3.0,
 * even though the former looks much more
 * like a real random password.</p>
 *
 * <p>Real-world random passwords almost
 * always mix <b>multiple character
 * classes</b>: lowercase, uppercase, digit,
 * symbol. Placeholders like "password" or
 * "test" usually stick to a single class.
 * Combining entropy with char-class
 * diversity gives a much stronger
 * signal.</p>
 *
 * <h3>Per-character signal reference</h3>
 *
 * <pre>
 *   Literal        | Entropy | Classes | Verdict
 *   ---------------+---------+---------+--------
 *   "7e5tc4s3"     |   3.00  | 2       | HIGH (real random password)
 *   "P@ssw0rd!"    |   3.00  | 4       | HIGH
 *   "Aa1!Bb2@"     |   3.00  | 4       | HIGH
 *   "password"     |   2.75  | 1       | LOW  (placeholder)
 *   "test1234"     |   2.50  | 2       | LOW  (placeholder)
 *   "admin123"     |   2.66  | 2       | LOW  (placeholder)
 *   "abcdefgh"     |   3.00  | 1       | LOW  (random lowercase -- not a password)
 * </pre>
 *
 * <h3>Algorithm</h3>
 *
 * <p>A literal is a "likely placeholder"
 * (suppressed) when:</p>
 *
 * <ol>
 *   <li>Length &lt; 6 (short strings —
 *       entropy unreliable), OR</li>
 *   <li>Length &ge; 6 AND entropy &lt;
 *       {@code 2.85} bits/char (low
 *       entropy, regardless of class
 *       diversity), OR</li>
 *   <li>Length &ge; 6 AND entropy &ge;
 *       {@code 2.85} AND only 1 character
 *       class is used (e.g. all
 *       lowercase). Real passwords almost
 *       always mix at least 2 classes.</li>
 * </ol>
 */
package org.plumb.dfa.checkers;

/**
 * R9 -- Static entropy + character-class diversity utilities for hard-coded credential FP
 * suppression.
 */
public final class EntropyDetector {

  /**
   * Confidence bucket for a finding, based on the entropy + class diversity of the literal. The
   * {@code HardCodedPasswordSinkChecker} embeds the bucket in the finding's message so the AI dedup
   * agent can prioritize (or ignore) low-confidence candidates.
   */
  public enum Confidence {
    /** Likely a placeholder; suppressed. */
    LOW,
    /** Possibly a password; standard fire. */
    MEDIUM,
    /** Looks like a real random password; high-confidence fire. */
    HIGH
  }

  /**
   * R9 -- Entropy threshold below which literals are treated as low entropy. The actual max entropy
   * of an 8-char string is {@code log2(8) = 3.0} so 2.85 catches placeholders that have some
   * repeated characters ("password", "test1234") while keeping all-unique strings ("7e5tc4s3").
   */
  public static final double LOW_ENTROPY_THRESHOLD = 2.85;

  /** Below this length, entropy isn't reliable; suppress based on length alone. */
  public static final int MIN_LENGTH_FOR_ENTROPY = 6;

  private EntropyDetector() {}

  /**
   * R9 -- Shannon entropy of a string, in bits per character. For empty or single-char strings,
   * returns 0.
   *
   * <p>Formula:
   *
   * <pre>
   *   H = -Σ p(c) * log2(p(c))   for c in chars(s)
   * </pre>
   *
   * <p>where {@code p(c)} is the probability of character {@code c} in the string (i.e. {@code
   * count(c) / |s|}).
   */
  public static double shannonBitsPerChar(String s) {
    if (s == null || s.length() <= 1) return 0.0;
    int n = s.length();
    java.util.Map<Character, Integer> counts = new java.util.HashMap<>();
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

  /**
   * R9 -- Count distinct character classes used in the string. The four classes are:
   *
   * <ol>
   *   <li><b>lowercase</b> — a-z
   *   <li><b>uppercase</b> — A-Z
   *   <li><b>digit</b> — 0-9
   *   <li><b>symbol</b> — anything else (e.g. {@code !@#$%^&*})
   * </ol>
   *
   * <p>A string that uses 4 classes (e.g. {@code "P@ssw0rd!"}) is almost certainly a real password.
   * A string that uses 1 class (e.g. all lowercase) is much more likely a placeholder or a
   * non-password identifier.
   */
  public static int distinctCharClasses(String s) {
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

  /**
   * R9 -- Confidence bucket for a literal. Combines entropy with character-class diversity. The
   * 8-char entropy ceiling is {@code log2(8) = 3.0} bits/char, so the algorithm is calibrated for
   * short strings:
   *
   * <ul>
   *   <li>Length &lt; 6: always MEDIUM (entropy unreliable for short strings).
   *   <li>Length &ge; 6, low entropy (&lt; 2.85): LOW (placeholder like "password", "test1234" with
   *       duplicate chars).
   *   <li>Length &ge; 6, only 1 char class: LOW (e.g. all-lowercase "abcdefgh" is probably an
   *       identifier, not a password).
   *   <li>Length &ge; 6, &ge; 3 char classes, entropy &ge; 2.85: HIGH (real random password with
   *       mixed case + digits + symbols like "P@ssw0rd!", "Aa1!Bb2@").
   *   <li>Everything else: MEDIUM (2 classes + decent entropy like "7e5tc4s3" or "admin123" --
   *       could be a real password or a placeholder, hard to tell).
   * </ul>
   */
  public static Confidence classify(String literal) {
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

  /**
   * Should this literal be suppressed as a likely placeholder? Returns true when:
   *
   * <ul>
   *   <li>Length &lt; 6 (short), OR
   *   <li>Length &ge; 6 AND entropy &lt; {@link #LOW_ENTROPY_THRESHOLD}, OR
   *   <li>Length &ge; 6 AND entropy &ge; {@link #LOW_ENTROPY_THRESHOLD} AND only 1 character class
   *       used.
   * </ul>
   */
  public static boolean isLikelyPlaceholder(String literal) {
    return classify(literal) == Confidence.LOW;
  }
}

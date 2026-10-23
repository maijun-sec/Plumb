package chain;

/**
 * LAYER 3 / FILE 5 — string transformations.
 *
 * <p>Each method applies one more transformation so taint has to survive a growing number of string
 * operations. The chain deliberately includes operations that are *not* guaranteed to be modelled
 * by every taint engine (substring with a variable length, StringBuilder chaining, regex replaceAll,
 * String.format, split+join) so that this fixture measures real coverage rather than just the easy
 * concatenation case.
 */
public class TextOps {

  /** Plain pass-through. */
  public static String passthrough(String v) {
    return v;
  }

  /** Concatenation with a constant prefix. */
  public static String prefix(String v) {
    return "user:" + v;
  }

  /** Concatenation with a constant suffix. */
  public static String suffix(String v) {
    return v + ":end";
  }

  /** Substring with a variable end index. */
  public static String clip(String v, int end) {
    if (v == null || end <= 0) {
      return "";
    }
    return v.substring(0, Math.min(end, v.length()));
  }

  /** {@code StringBuilder} accumulation — a mutable local buffer. */
  public static String build(String v) {
    StringBuilder sb = new StringBuilder();
    sb.append("q=");
    sb.append(v);
    sb.append("&submit=1");
    return sb.toString();
  }

  /** Chained {@code StringBuilder} calls. */
  public static String buildChained(String v) {
    return new StringBuilder().append(v).append(';').toString();
  }

  /** Regex replace — the value is both an operand and a subject. */
  public static String scrub(String v) {
    return v.replaceAll("[^A-Za-z0-9]", "_");
  }

  /** {@code String.format} with a tainted format-argument (not the format string itself). */
  public static String format(String v) {
    return String.format("%s", v);
  }

  /**
   * TAINT IN THE FORMAT STRING. This is a genuine CWE-134 format-string sink: the attacker controls
   * the format, not just an argument. Expected to be reported by the format-string checker.
   */
  public static String formatTainted(String fmt) {
    return String.format(fmt);
  }

  /** split + join — the value passes through an array. */
  public static String splitJoin(String v) {
    String[] parts = v.split(",");
    return String.join("|", parts);
  }

  /** toUpperCase / trim — value-preserving transforms. */
  public static String normalize(String v) {
    return v.trim().toUpperCase();
  }

  /** A transform that genuinely destroys the value: this is the SANITIZER marker hop. */
  public static String sanitize(String v) {
    if (v == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (c >= 'a' && c <= 'z') {
        sb.append((char) (c - 32));
      } else if (c >= 'A' && c <= 'Z') {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
package chain;

/**
 * LAYER 4 / FILE 7 — validation / normalisation layer.
 *
 * <p>This is where taint is supposed to be *checked* rather than merely carried. Two of the four
 * methods genuinely destroy the attacker control (real sanitizers), and two do not. The pair of
 * matched methods lets the support report distinguish:
 *
 * <ul>
 *   <li>real sanitizers → the downstream sink SHOULD NOT be reported
 *   <li>fake sanitizers (re-validate but still pass the tainted value) → SHOULD be reported
 * </ul>
 *
 * <p>A checker with no sanitizer awareness reports all four; a checker that over-approximates
 * "sanitizer" as "any method whose name contains validate" reports none. The correct answer is in
 * the middle, which is why this layer exists.
 */
public class Validator {

  /**
   * REAL sanitizer: returns only a fixed literal, discarding the input entirely. A downstream SQL
   * sink reached through this method is a false positive if reported.
   */
  public String hardAllowlist(String raw) {
    if ("read".equals(raw) || "write".equals(raw) || "admin".equals(raw)) {
      return raw;
    }
    return "denied";
  }

  /**
   * FAKE sanitizer: validates the shape but then returns the ORIGINAL value. The taint survives and
   * the downstream sink MUST still be reported. This is the classic "validation that validates
   * nothing" bug.
   */
  public String weakValidate(String raw) {
    if (raw == null || raw.isEmpty()) {
      return "empty";
    }
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
      if (!ok) {
        // Rejects, but then still returns the tainted input anyway.
        return raw;
      }
    }
    return raw;
  }

  /**
   * REAL sanitizer for numbers only: returns a parsed int, so the value is no longer a string and
   * can no longer carry SQL-injection payload structure.
   */
  public int parseBoundedInt(String raw) {
    try {
      int v = Integer.parseInt(raw);
      if (v < 0) {
        return 0;
      }
      return v;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Length clamp. This TRUNCATES the value, which technically bounds it, but the prefix is still
   * attacker-controlled — a correct analysis treats this as taint-preserving, not sanitizing.
   */
  public String clamp(String raw) {
    if (raw == null) {
      return "";
    }
    if (raw.length() > 16) {
      return raw.substring(0, 16);
    }
    return raw;
  }

  /** No-op passthrough named to look like a sanitizer. Should NOT suppress the sink. */
  public String sanitizeAndPass(String raw) {
    return raw == null ? "" : raw.trim();
  }
}
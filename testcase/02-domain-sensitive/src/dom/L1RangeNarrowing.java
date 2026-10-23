package dom;

/**
 * LAYER 1 - numeric range narrowing inside a single method.
 *
 * <p>Plumb models numeric facts as a {@code Range} abstraction (see
 * {@code plumb-dfa/src/main/java/org/plumb/dfa/checkers/boundary/Range.java}) and checks it against
 * array indices, allocation sizes, and divisors. The question this file answers is: does a range
 * narrowed by a guard actually suppress the downstream boundary finding?
 *
 * <p>Each pair below is a matched (safe, unsafe) couple. The safe variant MUST stay silent if
 * range reasoning works; the unsafe variant MUST fire. Reporting both means the range domain is
 * not consulted and the checker is purely syntactic.
 */
public class L1RangeNarrowing {

  /** SAFE: index guarded to [0,3], array has length 3. Expect NO array-bounds finding. */
  public String safeAfterGuard(int index) {
    String[] arr = new String[3];
    if (index < 0 || index > 2) {
      return null;
    }
    return arr[index];
  }

  /** UNSAFE: same array, no guard. Expect array-bounds finding (CWE-129 / CWE-125). */
  public String unsafeWithoutGuard(int index) {
    String[] arr = new String[3];
    return arr[index];
  }

  /** SAFE: divisor checked non-zero before use. Expect NO divide-by-zero. */
  public int safeAfterDivisorCheck(int a, int b) {
    if (b == 0) {
      return 0;
    }
    return a / b;
  }

  /** UNSAFE: divisor unchecked. Expect divide-by-zero finding (CWE-369). */
  public int unsafeDivisor(int a, int b) {
    return a / b;
  }

  /**
   * SAFE via a range clamp: the index is forced into [0,2] by an explicit min/max rather than a
   * guard. This is the shape that a purely path-sensitive guard tracker misses but a real range
   * domain gets for free.
   */
  public String safeViaClamp(int index) {
    String[] arr = new String[3];
    int clamped = Math.max(0, Math.min(index, 2));
    return arr[clamped];
  }

  /**
   * UNSAFE: the clamp is computed but the ORIGINAL unguarded index is used. The presence of a
   * clamp nearby must not be mistaken for a clamp on the value actually used.
   */
  public String unsafeClampShadowed(int index) {
    String[] arr = new String[3];
    int clamped = Math.max(0, Math.min(index, 2));
    arr[clamped] = "x";
    return arr[index];
  }
}
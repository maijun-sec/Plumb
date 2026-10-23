package dom;

/**
 * LAYER 2 - the range domain has to survive one call boundary.
 *
 * <p>This is the first layer where "domain-sensitive" stops meaning "range inside one method". The
 * value leaves this method, is passed to a callee, and the callee must apply what the caller knows.
 *
 * <p>A per-method range analysis will fire on {@link #unsafeCallerIndex} (which reaches the array
 * load with no narrowing) and, if it does any interprocedural reasoning at all, stay silent on
 * {@link #safeCallerIndex}. Reporting the safe variant is the definitive signal that the range
 * domain is not propagated in summaries.
 */
public class L2CrossMethodRange {

  /** The callee indexes an array without narrowing. The CALLER is responsible for the bound. */
  public String indexNowhere(String[] arr, int index) {
    return arr[index];
  }

  /** SAFE: caller clamps before the call. Expect NO finding if the range is propagated. */
  public String safeCallerIndex(String[] arr, int index) {
    int safe = Math.max(0, Math.min(index, arr.length - 1));
    return indexNowhere(arr, safe);
  }

  /** UNSAFE: caller passes the raw index. Expect finding. */
  public String unsafeCallerIndex(String[] arr, int index) {
    return indexNowhere(arr, index);
  }

  /**
   * SAFE across two boundaries: the guard is in a helper, the index use is two frames away. Probes
   * whether range facts survive more than one summary hop.
   */
  public String safeTwoHopsAway(String[] arr, int index) {
    int once = guard(index, arr.length);
    return indexNowhere(arr, once);
  }

  /** UNSAFE: two hops, no guard anywhere. Expect finding. */
  public String unsafeTwoHopsAway(String[] arr, int index) {
    return indexNowhere(arr, guard(index, -1));
  }

  /** The guard helper. {@code limit < 0} means "no guard requested" so the unsafe path stays unsafe. */
  private int guard(int index, int limit) {
    if (limit < 0) {
      return index;
    }
    return Math.max(0, Math.min(index, limit - 1));
  }

  /** SAFE: divisor narrowed by the caller, used one frame down. */
  public int safeDivisorAcrossBoundary(int a, int b) {
    if (b == 0) {
      return 0;
    }
    return divideNowhere(a, b);
  }

  /** UNSAFE: no narrowing, division one frame down. */
  public int unsafeDivisorAcrossBoundary(int a, int b) {
    return divideNowhere(a, b);
  }

  private int divideNowhere(int a, int b) {
    return a / b;
  }
}
package dom;

/**
 * LAYER 4 - four-layer: control-flow merges that must combine range facts.
 *
 * <p>Layers 1-3 moved the value around. Layer 4 is different: it requires the domain to survive a
 * **CFG join**, where two paths contribute different range facts and the merge result matters.
 *
 * <p>This is the layer that distinguishes a true lattice from ad-hoc guards:
 *
 * <ul>
 *   <li>Both branches narrow to [0,1] -> the join is [0,1] -> the array access is SAFE.
 *   <li>One branch narrows, the other does not -> the join is [0,MAX] -> the access is UNSAFE.
 *   <li>One branch provably returns -> the join is only the other branch -> SAFE.
 * </ul>
 *
 * <p>A merge that always takes the union (the {@code TaintDomain.join} behaviour) will report all
 * three as unsafe. That is sound but imprecise, and this file measures exactly how imprecise.
 */
public class L4ControlFlowMerge {

  private String use(String[] arr, int i) {
    return arr[i];
  }

  /**
   * SAFE (both branches narrow): each path clamps into [0,1], so the join is [0,1] and the array of
   * length 2 is safe. Expect NO finding under a correct merge.
   */
  public String safeBothBranchesClamped(String[] arr, int index, boolean flag) {
    int v;
    if (flag) {
      v = Math.max(0, Math.min(index, 1));
    } else {
      v = Math.max(0, Math.min(index, 1));
    }
    return use(arr, v);
  }

  /**
   * UNSAFE (only one branch narrows): the union of [0,1] and the unbounded value is unbounded, so
   * the access must be flagged. Expect finding.
   */
  public String unsafeOnlyOneBranchClamped(String[] arr, int index, boolean flag) {
    int v;
    if (flag) {
      v = Math.max(0, Math.min(index, 1));
    } else {
      v = index;
    }
    return use(arr, v);
  }

  /**
   * SAFE (one branch returns): the join point is unreachable on the returning path, so only the
   * narrowed value can reach the array. Expect NO finding.
   */
  public String safeOneBranchReturns(String[] arr, int index, boolean flag) {
    if (flag) {
      return null;
    }
    return use(arr, Math.max(0, Math.min(index, arr.length - 1)));
  }

  /**
   * UNSAFE (both branches return a value, only one clamped). The join collects both.
   */
  public String unsafeBothBranchesReturn(String[] arr, int index, boolean flag) {
    int v = flag ? Math.max(0, Math.min(index, 1)) : index;
    return use(arr, v);
  }

  /** SAFE: a loop invariant clamp applied before the loop, so every iteration is bounded. */
  public String safeLoopInvariant(String[] arr, int index) {
    int v = Math.max(0, Math.min(index, arr.length - 1));
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 3; i++) {
      sb.append(use(arr, v));
    }
    return sb.toString();
  }

  /** UNSAFE: the clamp is INSIDE the loop and depends on the counter, so it does not bound index. */
  public String unsafeLoopLocalClamp(String[] arr, int index) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 3; i++) {
      sb.append(use(arr, Math.min(index, i)));
    }
    return sb.toString();
  }

  /**
   * SAFE: switch dispatch where every reachable case narrows. The default throws, so no unbounded
   * value reaches the access.
   */
  public String safeSwitchAllCases(String[] arr, int index, int mode) {
    int v;
    switch (mode) {
      case 0 -> v = Math.max(0, Math.min(index, 1));
      case 1 -> v = Math.max(0, Math.min(index, 0));
      default -> throw new IllegalArgumentException("bad mode");
    }
    return use(arr, v);
  }

  /**
   * UNSAFE: the switch default assigns the raw value, so the join is unbounded even though every
   * named case narrows. A checker that only inspects the named cases will get this wrong.
   */
  public String unsafeSwitchDefaultRaw(String[] arr, int index, int mode) {
    int v;
    switch (mode) {
      case 0 -> v = Math.max(0, Math.min(index, 1));
      case 1 -> v = Math.max(0, Math.min(index, 0));
      default -> v = index;
    }
    return use(arr, v);
  }

  /**
   * SAFE: early-return guard, the most common real-world shape. Every path that reaches the
   * access has passed the check.
   */
  public String safeEarlyReturn(String[] arr, int index) {
    if (index < 0 || index >= arr.length) {
      return null;
    }
    return use(arr, index);
  }

  /**
   * UNSAFE: the check exists but is inverted, so it guards the wrong side. Probes whether the
   * boundary checker reads the branch polarity or only the presence of a comparison.
   */
  public String unsafeInvertedGuard(String[] arr, int index) {
    if (index >= 0 && index < arr.length) {
      return use(arr, index);
    }
    return null;
  }
}
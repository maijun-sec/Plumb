package dom;

/**
 * LAYER 3 - a three-layer abstraction over numeric values.
 *
 * <p>Layer 1 narrows in the method. Layer 2 carries it across a call. Layer 3 carries it across a
 * call AND an object field AND a container. Each extra indirection is a chance for a domain fact to
 * be dropped, so the three matched pairs isolate where it dies.
 *
 * <p>This layer is the one that separates "has a range domain" from "has a domain-sensitive
 * analysis": a real domain would track the numeric fact through the field store/load and the
 * collection round-trip.
 */
public class L3LayeredValue {

  /** A boxed holder: the numeric fact has to survive the box and the unbox. */
  public static class Holder {
    private int value;
    private Integer boxed;

    public Holder(int value) {
      this.value = value;
      this.boxed = Integer.valueOf(value);
    }

    public int getValue() {
      return value;
    }

    public Integer getBoxed() {
      return boxed;
    }

    public void setValue(int value) {
      this.value = value;
    }
  }

  /** A named range with both ends stored, so the fact lives in two fields at once. */
  public static class Range2 {
    private final int lo;
    private final int hi;

    public Range2(int lo, int hi) {
      this.lo = lo;
      this.hi = hi;
    }

    public int lo() {
      return lo;
    }

    public int hi() {
      return hi;
    }

    public int clamp(int v) {
      return Math.max(lo, Math.min(v, hi));
    }

    public boolean contains(int v) {
      return v >= lo && v <= hi;
    }
  }

  /** Indexes without narrowing. Callers are responsible for the bound. */
  private String use(String[] arr, int i) {
    return arr[i];
  }

  /** SAFE: narrowed in this method, stored in a field, read back, used. Expect NO finding. */
  public String safeThroughField(String[] arr, int index) {
    Holder h = new Holder(Math.max(0, Math.min(index, arr.length - 1)));
    return use(arr, h.getValue());
  }

  /** UNSAFE: the holder stores an UNguarded index; only the sibling field is clamped. */
  public String unsafeThroughField(String[] arr, int index) {
    Holder h = new Holder(index);
    h.setValue(Math.max(0, Math.min(index, arr.length - 1)));
    return use(arr, h.getValue());
  }

  /** SAFE: the narrowing lives in the Range2 object, one call away. Expect NO finding. */
  public String safeThroughRangeObject(String[] arr, int index, Range2 r) {
    return use(arr, r.clamp(index));
  }

  /**
   * UNSAFE: {@code contains} is CHECKED but the value is then passed raw. This is the classic
   * "validate but do not enforce" bug at the domain level, and it is what separates a real domain
   * (which would keep the narrowed value) from a boolean check (which does not).
   */
  public String unsafeContainsButNotApplied(String[] arr, int index, Range2 r) {
    if (r.contains(index)) {
      return use(arr, index);
    }
    return null;
  }

  /** SAFE: the value goes through a collection and back. Expect NO finding if domains survive it. */
  public String safeThroughCollection(String[] arr, int index) {
    java.util.List<Integer> holder = new java.util.ArrayList<>();
    holder.add(Math.max(0, Math.min(index, arr.length - 1)));
    return use(arr, holder.get(0));
  }

  /** UNSAFE: the collection stores the raw index. */
  public String unsafeThroughCollection(String[] arr, int index) {
    java.util.List<Integer> holder = new java.util.ArrayList<>();
    holder.add(index);
    return use(arr, holder.get(0));
  }

  /** SAFE: the domain travels through two object fields and a getter chain. */
  public String safeThroughTwoFields(String[] arr, int index) {
    Holder outer = new Holder(index);
    Holder inner = new Holder(outer.getValue());
    inner.setValue(Math.max(0, Math.min(index, arr.length - 1)));
    return use(arr, inner.getValue());
  }

  /** SAFE: boxing and unboxing round-trip. */
  public String safeThroughBoxing(String[] arr, int index) {
    Integer boxed = Integer.valueOf(Math.max(0, Math.min(index, arr.length - 1)));
    return use(arr, boxed.intValue());
  }

  /** UNSAFE: the box carries the raw index; only a different variable is clamped. */
  public String unsafeThroughBoxing(String[] arr, int index) {
    int clamped = Math.max(0, Math.min(index, arr.length - 1));
    Integer boxed = Integer.valueOf(index);
    arr[clamped] = "x";
    return use(arr, boxed.intValue());
  }
}
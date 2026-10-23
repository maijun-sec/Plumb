package ptr;

/**
 * LAYER 5: re-assignment.
 *
 * <p>A local that is assigned twice points at two objects at two program points. An analysis keyed
 * on the variable name alone unions them, so a reference captured before the second assignment is
 * treated as the second object -- and a mutation through the old value is then attributed to the
 * new one. This layer separates "which object does this name denote here" from "what did it denote
 * before".
 */
public class L5ReassignmentSsaGap {

  /** UNSAFE: the pre-assignment object is still reachable through `saved`. */
  public int firstValueReachedAfterReassignment(String a, String b) {
    StringBuilder current = new StringBuilder(a);
    StringBuilder saved = current;
    current = new StringBuilder(b);          // `current` now denotes the second object
    current.append("-new");
    return saved.length();                  // the FIRST object, untouched
  }

  /**
   * SAFE: each object is fully finished before the name moves on, so no reference outlives its
   * object's mutation.
   */
  public int cleanSequentialReassignment(String a, String b) {
    StringBuilder scratch = new StringBuilder(a);
    scratch.append("-done");
    int lengthOfFirst = scratch.length();
    scratch = new StringBuilder(b);
    scratch.append("-done");
    return lengthOfFirst + scratch.length();
  }

  /** UNSAFE: the alias is captured through a field rather than a local. */
  public int fieldHoldsTheOldObjectAfterReassignment(String a, String b) {
    this.retained = new StringBuilder(a);
    this.retained = new StringBuilder(b);
    this.retained.append("-new");
    return this.retained.length();
  }

  /** SAFE: the field is overwritten, so the caller's object is unaffected. */
  public int fieldOverwrittenBeforeUse(String a) {
    this.retained = new StringBuilder(a);
    this.retained = new StringBuilder("fresh");
    return this.retained.length();
  }

  private StringBuilder retained;
}
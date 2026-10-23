package ptr;

import java.util.HashMap;
import java.util.Map;

/**
 * LAYER 1: does aliasing cut both ways?
 *
 * <p>A points-to analysis that answers "yes" to everything satisfies every true-positive case while
 * being useless, so every layer in this category pairs a positive with a negative control that
 * differs only in whether the two references can be the same object.
 *
 * <p>The negative control here is {@link #independentObjectsAreNotAliased()}: two allocations, two
 * locals. An engine that conflates them reports a defect where there is none.
 */
public class L1BasicAlias {

  /** UNSAFE (alias defect): the copy is validated, then the original is mutated anyway. */
  public int aliasShareMutation(int index, String value) {
    StringBuilder checked = new StringBuilder(value);
    StringBuilder same = checked;                 // same object, different name
    if (same.length() > 0) {
      same.setCharAt(0, 'X');                     // the "validated" reference changes too
    }
    return checked.length();                      // caller observes an unexpected mutation
  }

  /** SAFE: two genuinely different objects. Mutating one must not affect the other. */
  public int independentObjectsAreNotAliased(int index, String a, String b) {
    StringBuilder first = new StringBuilder(a);
    StringBuilder second = new StringBuilder(b);
    if (second.length() > 0) {
      second.setCharAt(0, 'Y');
    }
    return first.length();
  }

  /**
   * SAFE: the same StringBuilder passes through a Map round trip, guarded on one name.
   *
   * <p>R1113: the {@code fetched.append(..)} here was an unguarded lookup reported as a null
   * dereference. Guarding {@code created} and dereferencing {@code fetched} is the alias-transfer
   * case this category exists to measure, so it is guarded properly rather than renamed.
   */
  public int aliasThroughContainer(String key) {
    // R1113: the container is allocated HERE rather than taken as a parameter. A container that
    // arrives as a parameter has no points-to set -- this method has no caller -- so its cells
    // cannot be modelled and the fixture would be measuring that gap instead of container aliasing.
    Map<String, StringBuilder> store = new HashMap<>();
    StringBuilder created = new StringBuilder("seed");
    store.put(key, created);
    StringBuilder fetched = store.get(key);
    if (created == null) {
      return 0;
    }
    fetched.append("-mutated");
    return created.length();                      // `created` grew without being told
  }

  /** SAFE: two entries in the map hold different objects. */
  public int independentEntriesInContainer() {
    Map<String, StringBuilder> store = new HashMap<>();
    StringBuilder left = new StringBuilder("l");
    StringBuilder right = new StringBuilder("r");
    store.put("a", left);
    store.put("b", right);
    left.append("!");
    return right.length();                        // unchanged
  }

  /**
   * SAFE, and actually safe: the parameter is a String, so it is checked before use.
   *
   * <p>R1113: this method used to read {@code alias.length()} with no null check while its name and
   * comment claimed safety. It was a real null dereference, and it made every file-level precision
   * gate in this category fail for a reason that had nothing to do with aliasing. A fixture that
   * lies about its own safety is worse than no fixture: it turns a measurement into a coin flip.
   */
  public int immutableValueIsSafe(String value) {
    if (value == null) {
      return 0;
    }
    String alias = value;
    return alias.length() + value.length();
  }

  /**
   * UNSAFE, and named for what it is: an unguarded container lookup.
   *
   * <p>R1113: the null check this used to carry lived in {@code unused(..)} on a different method, so
   * it never protected the {@code fetched.append(..)} below. It is kept as a genuine positive --
   * {@code Map.get} can return null for an absent key -- and is not counted by the precision gates,
   * which are scoped to the methods named in the manifest.
   */
  public int unguardedContainerLookup(String key) {
    Map<String, StringBuilder> store = new HashMap<>();
    StringBuilder created = new StringBuilder("seed");
    store.put(key, created);
    StringBuilder fetched = store.get(key);
    fetched.append("-mutated");
    return fetched.length();
  }
}
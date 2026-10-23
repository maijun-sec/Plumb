package ptr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LAYER 3: container-carried references.
 *
 * <p>Objects reached through a collection or a map are two steps from the variable that holds them,
 * and a points-to analysis that stops at the container variable loses everything behind it.
 *
 * <p>Index sensitivity is the precision question here: {@code list.get(0)} and {@code list.get(1)}
 * are distinct cells when the index is a literal, and must be conflated only when it is computed.
 */
public class L3CollectionCarrier {

  /** UNSAFE: one object put into a list, then reached back and mutated. */
  public int sameElementReachedTwice() {
    List<StringBuilder> list = new ArrayList<>();
    StringBuilder added = new StringBuilder("a");
    list.add(added);
    added.append("-one");
    StringBuilder fetched = list.get(0);
    fetched.append("-two");
    return added.length();
  }

  /** SAFE: two elements, one mutated. */
  public int distinctElementsDoNotAlias() {
    List<StringBuilder> list = new ArrayList<>();
    StringBuilder first = new StringBuilder("f");
    StringBuilder second = new StringBuilder("s");
    list.add(first);
    list.add(second);
    second.append("!");
    return first.length();
  }

  /**
   * SAFE, and actually safe: guarded on the stored name, dereferenced through the retrieved alias.
   *
   * <p>R1113: this method's comment said UNSAFE while the category used it as a precision gate, and
   * the unguarded {@code store.get("k").append(..)} made it a genuine null dereference. The guard
   * below is the alias transfer this layer exists to measure, so the method is made safe rather than
   * reclassified: a fixture whose comment contradicts its code makes every measurement downstream of
   * it meaningless.
   */
  public int mapRoundTripReturnsTheSameObject() {
    Map<String, StringBuilder> store = new HashMap<>();
    StringBuilder created = new StringBuilder("m");
    store.put("k", created);
    if (created == null) {
      return 0;
    }
    store.get("k").append("-via-map");
    return created.length();
  }

  /** SAFE: two keys, two objects. */
  public int mapKeysHoldDistinctObjects() {
    Map<String, StringBuilder> store = new HashMap<>();
    StringBuilder left = new StringBuilder("l");
    StringBuilder right = new StringBuilder("r");
    store.put("a", left);
    store.put("b", right);
    right.append("!");
    return left.length();
  }

  /**
   * SAFE by degradation: a computed index cannot be proven distinct from a literal one, so the
   * analysis is expected to conflate them. The finding (if any) is sound even though imprecise --
   * recorded separately so imprecision is visible instead of mistaken for a defect.
   */
  public int computedIndexDegradesToUnknown(String which) {
    List<StringBuilder> list = new ArrayList<>();
    StringBuilder stored = new StringBuilder("s");
    list.add(stored);
    int i = "first".equals(which) ? 0 : 1;
    StringBuilder fetched = list.get(i);
    if (fetched == null) {
      return 0;
    }
    fetched.append("!");
    return stored.length();
  }
}